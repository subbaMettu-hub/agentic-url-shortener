package com.assessment.orchestrator.core;

/**
 * The human-in-the-loop boundary. High-impact stages (e.g. release readiness) call through this
 * port instead of proceeding automatically - this is where "controlled autonomy" is enforced:
 * the agent can prepare and recommend, but this port is what actually authorizes the action.
 */
public interface HumanApprovalPort {
    ApprovalDecision requestApproval(ExecutionContext ctx, StageNode stage, StageOutcome outcome);
}
