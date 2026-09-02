package com.assessment.orchestrator.core;

import java.time.Instant;

/**
 * One entry in the run's decision lineage: an immutable, timestamped record of who (agent,
 * policy engine, or human) decided what and why. The lineage is what makes the run auditable -
 * every state transition traces back to a DecisionRecord.
 */
public record DecisionRecord(
        Instant timestamp,
        String actor,
        StageId stageId,
        String decision,
        String rationale,
        int planVersion
) {
    public static DecisionRecord of(String actor, StageId stageId, String decision, String rationale, int planVersion) {
        return new DecisionRecord(Instant.now(), actor, stageId, decision, rationale, planVersion);
    }
}
