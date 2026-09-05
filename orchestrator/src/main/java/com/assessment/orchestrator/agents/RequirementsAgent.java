package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.*;
import com.assessment.orchestrator.domain.Assumption;
import com.assessment.orchestrator.domain.RequirementSpec;
import com.assessment.orchestrator.llm.ClaudeReasoningProvider;
import com.assessment.orchestrator.llm.ReasoningProvider;
import com.assessment.orchestrator.util.ArtifactWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Stage 1: Requirement Understanding. Interprets the raw ask, flags ambiguity, and normalizes it
 * into an engineering-ready spec with documented assumptions rather than silent guesses.
 *
 * Analysis is delegated to a real Claude API call when a {@link ReasoningProvider} is available
 * (i.e. ANTHROPIC_API_KEY is configured); otherwise - and whenever the call fails or returns
 * something that doesn't parse - it falls back unconditionally to an explicit, inspectable
 * keyword heuristic. Either way the analysis method actually used is recorded in the decision
 * lineage and the generated report, so a run is never ambiguous about whether a stage's judgment
 * came from a model or from a fixed rule.
 *
 * If this stage is re-entered after a re-plan (a downstream stage flagged that an earlier
 * assumption doesn't hold), it produces a revision that explicitly resolves the flagged risk -
 * usually by consciously narrowing scope and documenting the trade-off, not by "fixing" the
 * requirement into something it never asked for. Revision always uses the deterministic
 * re-scoping logic below, not the LLM path, since it's a governance action (explicitly retracting
 * a specific flagged assumption) rather than open-ended analysis.
 */
public final class RequirementsAgent implements Agent {

    private static final List<String> AMBIGUOUS_MARKERS = List.of(
            "reliable", "reliability", "faster", "better", "improve", "robust", "scalable",
            "modern", "user-friendly", "user friendly", "optimi", "more secure", "high quality");

    private static final List<String> CONCRETE_TERMS = List.of(
            "endpoint", "qr", "alias", "cache", "concurrent", "race condition", "bug",
            "increment", "api", "schema", "field", "status code", "http");

    private static final String ANALYSIS_SYSTEM_PROMPT = """
            You are a senior software engineer performing requirement analysis for a Java/Spring \
            Boot URL-shortener service. Given a raw feature/change request, respond with ONLY a \
            single JSON object - no markdown code fences, no prose before or after - matching \
            exactly this shape:
            {
              "normalizedGoal": string,
              "ambiguous": boolean,
              "inScope": [string, ...],
              "outOfScope": [string, ...],
              "acceptanceCriteria": [string, ...],
              "assumptions": [{"id": string, "statement": string, "rationale": string, "riskLevel": "LOW"|"MEDIUM"|"HIGH"}, ...],
              "openQuestions": [string, ...]
            }
            Mark "ambiguous" true only if the request lacks a concrete, testable definition of done \
            and requires you to invent scope on your own judgment rather than restate what was asked. \
            Every assumption needs a rationale explaining why it was necessary, not just what was \
            assumed.""";

    private final ReasoningProvider reasoningProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    public RequirementsAgent() {
        this(new ClaudeReasoningProvider());
    }

    RequirementsAgent(ReasoningProvider reasoningProvider) {
        this.reasoningProvider = reasoningProvider;
    }

    private record NormalizationOutcome(RequirementSpec spec, String analysisMethod) {
    }

    @Override
    public StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber) {
        boolean isRevision = ctx.hasArtifact("requirementSpec");
        RequirementSpec spec;
        String analysisMethod;
        if (isRevision) {
            spec = reviseSpec(ctx);
            analysisMethod = "deterministic re-scoping (revision)";
        } else {
            NormalizationOutcome outcome = normalize(ctx.getRawRequirement());
            spec = outcome.spec();
            analysisMethod = outcome.analysisMethod();
        }

        String report = renderMarkdown(spec, isRevision, analysisMethod);
        ArtifactWriter.write(ctx.getRunOutputDir(), "01-requirements.md", report);

        String rationale;
        if (isRevision) {
            rationale = "Re-entered after design flagged assumption(s) as unsafe; narrowed scope and "
                    + "re-documented " + spec.assumptions().size() + " assumption(s) to resolve the conflict.";
        } else if (spec.ambiguous()) {
            rationale = "Raw ask was ambiguous; proceeded under " + spec.assumptions().size()
                    + " documented assumption(s). Analysis method: " + analysisMethod + ".";
        } else {
            rationale = "Raw ask was sufficiently well-defined; normalized directly into acceptance "
                    + "criteria. Analysis method: " + analysisMethod + ".";
        }
        ctx.recordDecision("RequirementsAgent", StageId.REQUIREMENTS,
                isRevision ? "REQUIREMENT_REVISED" : "REQUIREMENT_NORMALIZED", rationale);

        return StageOutcome.builder("Normalized requirement (revision " + spec.revision() + "): " + spec.normalizedGoal())
                .artifact("requirementSpec", spec)
                .build();
    }

    private NormalizationOutcome normalize(String raw) {
        if (reasoningProvider.isAvailable()) {
            Optional<RequirementSpec> llmSpec = tryLlmNormalize(raw);
            if (llmSpec.isPresent()) {
                return new NormalizationOutcome(llmSpec.get(), "Claude API (live reasoning)");
            }
        }
        return new NormalizationOutcome(heuristicNormalize(raw), "deterministic heuristic");
    }

    /**
     * Never throws: any failure (unavailable provider, network error, malformed/incomplete JSON)
     * results in {@code Optional.empty()} so {@link #normalize} falls back to the heuristic path.
     */
    private Optional<RequirementSpec> tryLlmNormalize(String raw) {
        Optional<String> response = reasoningProvider.complete(ANALYSIS_SYSTEM_PROMPT, raw);
        if (response.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(extractJson(response.get()));
            String goal = root.path("normalizedGoal").asText(null);
            if (goal == null || goal.isBlank()) {
                return Optional.empty();
            }
            List<Assumption> assumptions = new ArrayList<>();
            for (JsonNode a : root.path("assumptions")) {
                String id = a.path("id").asText(null);
                String statement = a.path("statement").asText(null);
                String rationale = a.path("rationale").asText(null);
                if (id == null || statement == null || rationale == null) {
                    continue;
                }
                String riskLevel = a.path("riskLevel").asText("MEDIUM").toUpperCase(Locale.ROOT);
                assumptions.add(new Assumption(id, statement, rationale, riskLevel));
            }
            RequirementSpec spec = new RequirementSpec(raw, goal,
                    textList(root.path("inScope")), textList(root.path("outOfScope")),
                    textList(root.path("acceptanceCriteria")), assumptions,
                    textList(root.path("openQuestions")), root.path("ambiguous").asBoolean(false), 1);
            return Optional.of(spec);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static List<String> textList(JsonNode arrayNode) {
        List<String> result = new ArrayList<>();
        if (arrayNode.isArray()) {
            for (JsonNode n : arrayNode) {
                String s = n.asText(null);
                if (s != null && !s.isBlank()) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    /** Defensively strips markdown code fences in case the model wraps its JSON despite instructions. */
    private static String extractJson(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private RequirementSpec heuristicNormalize(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        boolean hasAmbiguousMarker = AMBIGUOUS_MARKERS.stream().anyMatch(lower::contains);
        boolean hasConcreteTerm = CONCRETE_TERMS.stream().anyMatch(lower::contains);
        boolean ambiguous = hasAmbiguousMarker && !hasConcreteTerm;

        List<Assumption> assumptions = new ArrayList<>();
        List<String> openQuestions = new ArrayList<>();
        List<String> inScope = new ArrayList<>();
        List<String> outOfScope = new ArrayList<>();
        List<String> acceptance = new ArrayList<>();
        String goal;

        if (ambiguous) {
            goal = "Improve operational reliability of the URL shortener service within the "
                    + "boundaries of a single-instance prototype.";
            inScope.add("Bounded per-client rate limiting to protect against abusive/runaway callers");
            inScope.add("Consistent health/readiness reporting for the service");
            inScope.add("Input validation and structured error responses (already in place; verified, not re-built)");
            outOfScope.add("Multi-region failover / high availability - requires infrastructure this prototype doesn't have");
            outOfScope.add("SLA-backed latency targets - no production traffic baseline to target against");
            acceptance.add("Rate limiting can be enabled via configuration without a code change");
            acceptance.add("A client that exceeds its quota receives HTTP 429 with a structured error body");
            acceptance.add("/actuator/health reports UP/DOWN reflecting real dependency health");
            assumptions.add(new Assumption("A1",
                    "\"Reliable\" is interpreted as: bounded abuse protection + accurate health signaling, "
                            + "not high availability or a specific latency SLA.",
                    "The raw requirement gives no metric, target, or failure mode - a concrete scope has to be chosen "
                            + "and documented rather than guessed at implementation time.",
                    "MEDIUM"));
            assumptions.add(new Assumption("A2",
                    "An in-memory, single-node token-bucket rate limiter is sufficient for this iteration.",
                    "No shared-state store (e.g. Redis) is provisioned for this prototype; a distributed limiter "
                            + "is out of scope unless the deployment target requires multiple instances.",
                    "HIGH"));
            openQuestions.add("Is this service expected to run as more than one instance in production? "
                    + "That would invalidate assumption A2 and require a shared rate-limit store.");
            openQuestions.add("Is there a target availability/latency SLA this should be validated against?");
        } else {
            goal = raw.length() > 160 ? raw.substring(0, 160) + "..." : raw;
            inScope.add("The specific behavior described in the raw requirement");
            acceptance.add("New/changed endpoint(s) behave as described and are covered by tests");
            assumptions.add(new Assumption("A1",
                    "Default values not specified in the request (formats, sizes, TTLs) follow this "
                            + "codebase's existing conventions rather than introducing new ones.",
                    "Keeps the change consistent with the rest of the service instead of a bespoke one-off choice.",
                    "LOW"));
        }

        return new RequirementSpec(raw, goal, inScope, outOfScope, acceptance, assumptions, openQuestions, ambiguous, 1);
    }

    private RequirementSpec reviseSpec(ExecutionContext ctx) {
        RequirementSpec previous = ctx.getArtifact("requirementSpec");
        List<Assumption> revisedAssumptions = new ArrayList<>();
        for (Assumption a : previous.assumptions()) {
            if ("HIGH".equals(a.riskLevel())) {
                revisedAssumptions.add(new Assumption(a.id(),
                        a.statement() + " [REVISED: explicitly accepted as a documented MVP limitation, "
                                + "not silently carried forward]",
                        "Design review found this assumption doesn't hold if the service is ever run with more than "
                                + "one instance. Rather than building distributed rate limiting under an ambiguous "
                                + "requirement, the scope is narrowed on purpose and the limitation is called out "
                                + "in docs/ARCHITECTURE.md so it's a conscious trade-off, not a hidden gap.",
                        "LOW (accepted)"));
            } else {
                revisedAssumptions.add(a);
            }
        }
        List<String> openQuestions = new ArrayList<>(previous.openQuestions());
        openQuestions.replaceAll(q -> q.contains("more than one instance")
                ? q + " -> RESOLVED: out of scope for this iteration, tracked as a known limitation."
                : q);

        return new RequirementSpec(previous.rawRequirement(), previous.normalizedGoal(), previous.inScope(),
                previous.outOfScope(), previous.acceptanceCriteria(), revisedAssumptions, openQuestions,
                false, previous.revision() + 1);
    }

    private String renderMarkdown(RequirementSpec spec, boolean isRevision, String analysisMethod) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Requirements - ").append(isRevision ? "Revision " + spec.revision() : "Initial normalization").append("\n\n");
        sb.append("**Analysis method:** ").append(analysisMethod).append("\n\n");
        sb.append("**Raw requirement:** ").append(spec.rawRequirement()).append("\n\n");
        sb.append("**Normalized goal:** ").append(spec.normalizedGoal()).append("\n\n");
        sb.append("**Ambiguous:** ").append(spec.ambiguous()).append("\n\n");
        sb.append("## In scope\n");
        spec.inScope().forEach(s -> sb.append("- ").append(s).append("\n"));
        sb.append("\n## Out of scope\n");
        spec.outOfScope().forEach(s -> sb.append("- ").append(s).append("\n"));
        sb.append("\n## Acceptance criteria\n");
        spec.acceptanceCriteria().forEach(s -> sb.append("- ").append(s).append("\n"));
        sb.append("\n## Documented assumptions\n");
        for (Assumption a : spec.assumptions()) {
            sb.append("- **").append(a.id()).append("** [").append(a.riskLevel()).append("] ")
                    .append(a.statement()).append("\n  - Rationale: ").append(a.rationale()).append("\n");
        }
        sb.append("\n## Open questions\n");
        if (spec.openQuestions().isEmpty()) {
            sb.append("- None\n");
        } else {
            spec.openQuestions().forEach(q -> sb.append("- ").append(q).append("\n"));
        }
        return sb.toString();
    }
}
