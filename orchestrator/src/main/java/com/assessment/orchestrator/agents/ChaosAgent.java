package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.Agent;
import com.assessment.orchestrator.core.ExecutionContext;
import com.assessment.orchestrator.core.StageNode;
import com.assessment.orchestrator.core.StageOutcome;

/**
 * Failure-injection decorator used only via the CLI's {@code --inject-failure} flag, to give
 * reviewers an explicit, opt-in way to see the retry-exhaustion -> rollback -> safe-stop path
 * without baking a manufactured failure into the three primary scenario narratives (which are
 * meant to demonstrate the governance-success path). Never wired in by default.
 */
public final class ChaosAgent implements Agent {

    private final String stageLabel;

    public ChaosAgent(String stageLabel) {
        this.stageLabel = stageLabel;
    }

    @Override
    public StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber) {
        return StageOutcome.failure("Injected failure via --inject-failure=" + stageLabel
                + " (attempt " + attemptNumber + ") - simulating an unrecoverable agent failure");
    }
}
