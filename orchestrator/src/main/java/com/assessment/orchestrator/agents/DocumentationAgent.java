package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.*;
import com.assessment.orchestrator.domain.RequirementSpec;
import com.assessment.orchestrator.util.ArtifactWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/**
 * Runs in parallel with {@link TestingAgent} (both depend only on Implementation + Static
 * Analysis having passed) - documentation doesn't need test results to be written, so there's no
 * reason to serialize it behind the test run.
 */
public final class DocumentationAgent implements Agent {

    @Override
    @SuppressWarnings("unchecked")
    public StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber) {
        RequirementSpec spec = ctx.getArtifact("requirementSpec");
        List<String> impacted = ctx.hasArtifact("apiDesignImpactedFiles")
                ? ctx.getArtifact("apiDesignImpactedFiles") : List.of();

        StringBuilder sb = new StringBuilder();
        sb.append("# Documentation - ").append(ctx.getScenarioName()).append("\n\n");
        sb.append("**Summary:** ").append(spec.normalizedGoal()).append("\n\n");
        sb.append("**Files impacted:** ").append(impacted.isEmpty() ? "none (additive)" : impacted).append("\n\n");
        sb.append("**Acceptance criteria:**\n");
        spec.acceptanceCriteria().forEach(a -> sb.append("- ").append(a).append("\n"));
        Path written = ArtifactWriter.write(ctx.getRunOutputDir(), "05-documentation.md", sb.toString());

        Path changelog = (Path) ctx.getConfig("changelogFile");
        if (changelog != null) {
            appendChangelogEntry(changelog, spec);
        }

        ctx.recordDecision("DocumentationAgent", StageId.DOCUMENTATION, "DOCS_WRITTEN",
                "Wrote " + written.getFileName() + (changelog != null ? " and appended CHANGELOG entry" : ""));

        return StageOutcome.builder("Documentation written")
                .artifact("documentationWritten", true)
                .build();
    }

    private void appendChangelogEntry(Path changelog, RequirementSpec spec) {
        String entry = "\n## " + Instant.now() + "\n\n" + spec.normalizedGoal() + "\n";
        try {
            Files.writeString(changelog, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
