package com.assessment.orchestrator.core;

public enum AuditEventType {
    RUN_START,
    RUN_END,
    STAGE_ENTRY_GATE_BLOCKED,
    STAGE_START,
    STAGE_RETRY,
    STAGE_PASS,
    STAGE_FAIL,
    STAGE_EXIT_GATE_BLOCKED,
    POLICY_VIOLATION,
    APPROVAL_REQUESTED,
    APPROVAL_DECISION,
    ROLLBACK,
    REPLAN,
    STAGE_STALE,
    SAFE_STOP
}
