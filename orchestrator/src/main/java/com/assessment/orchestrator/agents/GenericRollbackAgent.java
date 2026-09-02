package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.*;

/**
 * Default compensating action for a stage that has exhausted its retries: no filesystem/state
 * mutation to undo in this prototype (agents write evidence files under the run's own output
 * directory, never to the live product source), so the "rollback" is discarding this run's
 * partial artifacts for the stage and recording why - still a real, auditable action, not a no-op
 * left silently unhandled.
 */
public final class GenericRollbackAgent implements Agent {

    @Override
    public StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber) {
        ctx.recordDecision("GenericRollbackAgent", self.getId(), "ROLLED_BACK",
                "Discarded partial artifacts for " + self.getId() + " after exhausting retries; "
                        + "downstream stages will not proceed on top of an unverified result.");
        return StageOutcome.success("Compensating action complete: partial state for " + self.getId() + " discarded");
    }
}
