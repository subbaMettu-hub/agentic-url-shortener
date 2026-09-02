package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.*;
import com.assessment.orchestrator.domain.Assumption;
import com.assessment.orchestrator.domain.RequirementSpec;
import com.assessment.orchestrator.util.ArtifactWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Stage 2a (parallel with {@link DesignDataModelAgent}): API-surface design. For a brownfield
 * requirement, also performs real codebase reasoning - it greps the actual url-shortener source
 * tree for concepts named in the requirement and lists the files/classes that will be impacted,
 * rather than describing changes in the abstract.
 *
 * This is also where the dynamic re-planning trigger lives for the ambiguous scenario: if the
 * normalized spec still carries a HIGH-risk assumption after the first pass, design review treats
 * that as a blocking design constraint and hands control back to Requirements rather than quietly
 * building on top of a shaky assumption.
 */
public final class DesignApiAgent implements Agent {

    @Override
    public StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber) throws Exception {
        RequirementSpec spec = ctx.getArtifact("requirementSpec");

        boolean unresolvedHighRisk = spec.revision() == 1
                && spec.assumptions().stream().anyMatch(a -> "HIGH".equals(a.riskLevel()));
        if (unresolvedHighRisk) {
            Assumption flagged = spec.assumptions().stream()
                    .filter(a -> "HIGH".equals(a.riskLevel())).findFirst().orElseThrow();
            ctx.recordDecision("DesignApiAgent", StageId.DESIGN_API, "DESIGN_CONSTRAINT_VIOLATION",
                    "Assumption " + flagged.id() + " (\"" + flagged.statement() + "\") is high-risk and unresolved; "
                            + "design cannot safely proceed on top of it. Sending back to Requirements.");
            return StageOutcome.builder("Blocked: high-risk assumption " + flagged.id() + " must be resolved before API design proceeds")
                    .success(true)
                    .requiresReplan(StageId.REQUIREMENTS,
                            "Design review found assumption " + flagged.id() + " (\"" + flagged.statement()
                                    + "\") is not safe to build on without an explicit scope decision.")
                    .build();
        }

        List<String> impactedFiles = findImpactedFiles(ctx);
        String design = renderDesign(spec, impactedFiles);
        ArtifactWriter.write(ctx.getRunOutputDir(), "02a-design-api.md", design);

        ctx.recordDecision("DesignApiAgent", StageId.DESIGN_API, "API_DESIGN_COMPLETE",
                impactedFiles.isEmpty()
                        ? "New surface area; no existing endpoints/classes impacted."
                        : "Impact analysis identified " + impactedFiles.size() + " existing file(s) to change: " + impactedFiles);

        return StageOutcome.builder("API design complete; " + impactedFiles.size() + " existing file(s) identified as impacted")
                .artifact("apiDesignImpactedFiles", impactedFiles)
                .build();
    }

    private List<String> findImpactedFiles(ExecutionContext ctx) {
        Path srcRoot = ctx.getRepoRoot();
        if (srcRoot == null || !Files.isDirectory(srcRoot)) {
            return List.of();
        }
        String lowerRequirement = ctx.getRawRequirement().toLowerCase(Locale.ROOT);
        List<String> keywords = new ArrayList<>();
        if (lowerRequirement.contains("click") || lowerRequirement.contains("analytics") || lowerRequirement.contains("count")) {
            keywords.add("clickcount");
            keywords.add("analytics");
        }
        if (lowerRequirement.contains("cache")) {
            keywords.add("cache");
        }
        if (lowerRequirement.contains("concurrent") || lowerRequirement.contains("race")) {
            keywords.add("redirect");
        }
        if (keywords.isEmpty()) {
            return List.of();
        }

        List<String> impacted = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        boolean nameMatch = keywords.stream().anyMatch(name::contains);
                        boolean contentMatch = false;
                        if (!nameMatch) {
                            try {
                                String content = Files.readString(p).toLowerCase(Locale.ROOT);
                                contentMatch = keywords.stream().anyMatch(content::contains);
                            } catch (IOException ignored) {
                                // best-effort reasoning over the codebase; skip unreadable files
                            }
                        }
                        if (nameMatch || contentMatch) {
                            impacted.add(srcRoot.relativize(p).toString().replace('\\', '/'));
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return impacted;
    }

    private String renderDesign(RequirementSpec spec, List<String> impactedFiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("# API Design\n\n");
        sb.append("**Goal:** ").append(spec.normalizedGoal()).append("\n\n");
        sb.append("## Codebase impact analysis\n");
        if (impactedFiles.isEmpty()) {
            sb.append("No existing files matched requirement keywords - this is additive/new surface area.\n");
        } else {
            impactedFiles.forEach(f -> sb.append("- `").append(f).append("`\n"));
        }
        sb.append("\n## Acceptance criteria driving this design\n");
        spec.acceptanceCriteria().forEach(a -> sb.append("- ").append(a).append("\n"));
        return sb.toString();
    }
}
