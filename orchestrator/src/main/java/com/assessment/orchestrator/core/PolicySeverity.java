package com.assessment.orchestrator.core;

public enum PolicySeverity {
    /** Non-blocking: logged and surfaced in the release readiness report, run proceeds. */
    WARN,
    /** Blocking: stage is failed immediately, regardless of what the agent returned. */
    BLOCK
}
