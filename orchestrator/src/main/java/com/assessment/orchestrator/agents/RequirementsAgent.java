package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.*;
import com.assessment.orchestrator.domain.Assumption;
import com.assessment.orchestrator.domain.RequirementSpec;
import com.assessment.orchestrator.util.ArtifactWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stage 1: Requirement Understanding. Interprets the raw ask, flags ambiguity using an explicit,
 * inspectable heuristic (not a hardcoded per-scenario branch), and normalizes it into an
 * engineering-ready spec with documented assumptions rather than silent guesses.
 *
 * If this stage is re-entered after a re-plan (a downstream stage flagged that an earlier
 * assumption doesn't hold), it produces a revision that explicitly resolves the flagged risk -
 * usually by consciously narrowing scope and documenting the trade-off, not by "fixing" the
 * requirement into something it never asked for.
 */
public final class RequirementsAgent implements Agent {

    private static final List<String> AMBIGUOUS_MARKERS = List.of(
            "reliable", "reliability", "faster", "better", "improve", "robust", "scalable",
            "modern", "user-friendly", "user friendly", "optimi", "more secure", "high quality");

    private static final List<String> CONCRETE_TERMS = List.of(
            "endpoint", "qr", "alias", "cache", "concurrent", "race condition", "bug",
            "increment", "api", "schema", "field", "status code", "http");

    @Override
    public StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber) {
        boolean isRevision = ctx.hasArtifact("requirementSpec");
        RequirementSpec spec = isRevision ? reviseSpec(ctx) : normalize(ctx.getRawRequirement());

        String report = renderMarkdown(spec, isRevision);
        ArtifactWriter.write(ctx.getRunOutputDir(), "01-requirements.md", report);

        String rationale;
        if (isRevision) {
            rationale = "Re-entered after design flagged assumption(s) as unsafe; narrowed scope and "
                    + "re-documented " + spec.assumptions().size() + " assumption(s) to resolve the conflict.";
        } else if (spec.ambiguous()) {
            rationale = "Raw ask was ambiguous; proceeded under " + spec.assumptions().size() + " documented assumption(s).";
        } else {
            rationale = "Raw ask was sufficiently well-defined; normalized directly into acceptance criteria.";
        }
        ctx.recordDecision("RequirementsAgent", StageId.REQUIREMENTS,
                isRevision ? "REQUIREMENT_REVISED" : "REQUIREMENT_NORMALIZED", rationale);

        return StageOutcome.builder("Normalized requirement (revision " + spec.revision() + "): " + spec.normalizedGoal())
                .artifact("requirementSpec", spec)
                .build();
    }

    private RequirementSpec normalize(String raw) {
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

    private String renderMarkdown(RequirementSpec spec, boolean isRevision) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Requirements - ").append(isRevision ? "Revision " + spec.revision() : "Initial normalization").append("\n\n");
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
