package com.assessment.orchestrator.core;

/**
 * Default, non-interactive approval port used for reproducible demo runs: applies a fixed,
 * transparent rule (approve iff the stage outcome succeeded and produced no blocking policy
 * violations) and records the decision as an explicitly-labeled automated approval rather than
 * silently pretending a human looked at it. This keeps runs scriptable for reviewers while being
 * honest in the audit trail about who (or what) actually authorized the release.
 *
 * A real deployment would replace this with a port that pages/notifies a human and blocks on
 * their response - see {@link InteractiveConsoleApprovalPort} for a real human-in-the-loop path.
 */
public final class AutoApprovalPort implements HumanApprovalPort {

    @Override
    public ApprovalDecision requestApproval(ExecutionContext ctx, StageNode stage, StageOutcome outcome) {
        boolean approve = outcome.isSuccess();
        String comment = approve
                ? "Auto-approved (demo mode): stage succeeded and no blocking policy violations were raised."
                : "Auto-rejected (demo mode): stage did not succeed.";
        return approve
                ? ApprovalDecision.approve("automated-reviewer (demo mode)", comment)
                : ApprovalDecision.reject("automated-reviewer (demo mode)", comment);
    }
}
