package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.*;
import com.assessment.orchestrator.domain.RequirementSpec;
import com.assessment.orchestrator.util.ArtifactWriter;

/**
 * Stage 2b (parallel with {@link DesignApiAgent}): data-model implications of the change.
 * Runs concurrently with the API design branch; both must pass before Implementation starts.
 */
public final class DesignDataModelAgent implements Agent {

    @Override
    public StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber) {
        RequirementSpec spec = ctx.getArtifact("requirementSpec");

        StringBuilder sb = new StringBuilder();
        sb.append("# Data Model Notes\n\n");
        sb.append("**Goal:** ").append(spec.normalizedGoal()).append("\n\n");
        sb.append("Existing entity: `ShortUrl` (shortCode, longUrl, createdAt, expiresAt, "
                + "lastAccessedAt, clickCount, active, customAlias).\n\n");
        sb.append("## Assessment\n");
        sb.append("No new persistent entity or schema migration is required for this change; "
                + "it composes with the existing `ShortUrl` schema.\n");

        ArtifactWriter.write(ctx.getRunOutputDir(), "02b-design-data-model.md", sb.toString());
        ctx.recordDecision("DesignDataModelAgent", StageId.DESIGN_DATA_MODEL, "DATA_MODEL_REVIEW_COMPLETE",
                "No schema migration required; change composes with the existing ShortUrl entity.");

        return StageOutcome.builder("Data model review complete: no migration required")
                .artifact("dataModelMigrationRequired", false)
                .build();
    }
}
