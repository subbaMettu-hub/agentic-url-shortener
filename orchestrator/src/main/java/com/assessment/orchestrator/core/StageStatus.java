package com.assessment.orchestrator.core;

/** Lifecycle states of a single stage within one workflow run. */
public enum StageStatus {
    PENDING,
    READY,
    RUNNING,
    RETRYING,
    AWAITING_APPROVAL,
    PASSED,
    FAILED,
    ROLLED_BACK,
    SKIPPED,
    STALE,
    BLOCKED_BY_POLICY
}
