package com.assessment.orchestrator.core;

/** Overall state of a workflow run. */
public enum RunStatus {
    RUNNING,
    COMPLETED,
    ABORTED,
    FAILED
}
