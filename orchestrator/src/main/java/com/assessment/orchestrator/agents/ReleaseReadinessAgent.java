package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.*;
import com.assessment.orchestrator.domain.RequirementSpec;
import com.assessment.orchestrator.util.ArtifactWriter;

import java.util.List;

/**
 * Aggregates every upstream signal into a release readiness report. This stage itself only
 * assembles and recommends - {@link StageNode#requiresHumanApproval()} on this node is what
 * actually gates the release, via the configured {@link HumanApprovalPort}. That split is
 * deliberate: the agent can be as opinionated as it likes, but it cannot authorize itself.
 */
public final class ReleaseReadinessAgent implements Agent {

    @Override
    @SuppressWarnings("unchecked")
    public StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber) {
        RequirementSpec spec = ctx.getArtifact("requirementSpec");
        int testsRun = ctx.hasArtifact("testsRun") ? ctx.getArtifact("testsRun") : 0;
        int testFailures = ctx.hasArtifact("testFailures") ? ctx.getArtifact("testFailures") : 0;
        List<String> findings = ctx.hasArtifact("staticAnalysisFindings") ? ctx.getArtifact("staticAnalysisFindings") : List.of();
        boolean docsWritten = ctx.hasArtifact("documentationWritten") && Boolean.TRUE.equals(ctx.getArtifact("documentationWritten"));

        StringBuilder sb = new StringBuilder();
        sb.append("# Release Readiness Report\n\n");
        sb.append("**Scenario:** ").append(ctx.getScenarioName()).append("\n");
        sb.append("**Goal:** ").append(spec.normalizedGoal()).append("\n\n");
        sb.append("| Signal | Result |\n|---|---|\n");
        sb.append("| Tests | ").append(testsRun).append(" run, ").append(testFailures).append(" failing |\n");
        sb.append("| Static analysis findings | ").append(findings.size()).append(" |\n");
        sb.append("| Documentation | ").append(docsWritten ? "present" : "MISSING").append(" |\n");
        sb.append("| Open questions carried to release | ").append(spec.openQuestions().size()).append(" |\n");
        ArtifactWriter.write(ctx.getRunOutputDir(), "06-release-readiness.md", sb.toString());

        boolean recommend = testFailures == 0 && docsWritten;
        ctx.recordDecision("ReleaseReadinessAgent", StageId.RELEASE_READINESS,
                recommend ? "RECOMMEND_RELEASE" : "RECOMMEND_HOLD",
                "tests_failing=" + testFailures + " docs_present=" + docsWritten);

        return StageOutcome.builder(recommend
                        ? "Recommending release: all gates satisfied"
                        : "Recommending HOLD: tests_failing=" + testFailures + " docs_present=" + docsWritten)
                .success(recommend)
                .build();
    }
}
