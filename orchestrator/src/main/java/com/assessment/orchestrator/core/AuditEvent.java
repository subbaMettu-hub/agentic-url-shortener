package com.assessment.orchestrator.core;

import java.time.Instant;
import java.util.Map;

public record AuditEvent(
        Instant timestamp,
        String runId,
        int planVersion,
        AuditEventType type,
        StageId stageId,
        String message,
        Map<String, Object> details
) {
}
