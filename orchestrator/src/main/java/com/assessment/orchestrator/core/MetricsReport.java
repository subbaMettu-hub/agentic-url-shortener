package com.assessment.orchestrator.core;

import java.util.Map;

public record MetricsReport(
        int totalStages,
        int passedStages,
        int failedStages,
        double successRate,
        int totalRetries,
        double retryFrequency,
        int totalRollbacks,
        double rollbackFrequency,
        long mttrMillis,
        long endToEndLatencyMillis,
        Map<String, MetricsCollector.StageMetricsSnapshot> perStage
) {
}
