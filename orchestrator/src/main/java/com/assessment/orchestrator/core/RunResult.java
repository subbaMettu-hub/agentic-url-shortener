package com.assessment.orchestrator.core;

import java.nio.file.Path;
import java.util.Map;

public record RunResult(
        String runId,
        RunStatus status,
        ExecutionContext context,
        MetricsReport metrics,
        Path auditLogPath,
        Map<StageId, StageStatus> finalStageStatuses
) {
}
