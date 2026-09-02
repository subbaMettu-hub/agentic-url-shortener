package com.assessment.orchestrator.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks reliability signals per stage as the run executes, then rolls them up into a
 * {@link MetricsReport}: success rate, retry/rollback frequency, MTTR, and end-to-end latency -
 * the metrics the assessment explicitly calls out.
 */
public final class MetricsCollector {

    private final Map<StageId, StageMetrics> perStage = new ConcurrentHashMap<>();
    private final long runStartNanos = System.nanoTime();
    private volatile long runEndNanos;

    private StageMetrics metricsFor(StageId id) {
        return perStage.computeIfAbsent(id, k -> new StageMetrics());
    }

    public void recordAttempt(StageId id) {
        metricsFor(id).attempts.incrementAndGet();
    }

    public void recordRetry(StageId id) {
        metricsFor(id).retries.incrementAndGet();
    }

    public void recordRollback(StageId id) {
        metricsFor(id).rollbacks.incrementAndGet();
    }

    public void recordFailure(StageId id) {
        StageMetrics m = metricsFor(id);
        m.firstFailureNanos.compareAndSet(0, System.nanoTime());
    }

    public void recordPass(StageId id, long durationMillis) {
        StageMetrics m = metricsFor(id);
        m.passed = true;
        m.totalDurationMillis.addAndGet(durationMillis);
    }

    public void markRunEnd() {
        this.runEndNanos = System.nanoTime();
    }

    public MetricsReport buildReport() {
        long endNanos = runEndNanos == 0 ? System.nanoTime() : runEndNanos;
        int total = perStage.size();
        int passed = 0;
        int totalRetries = 0;
        int totalRollbacks = 0;
        long mttrSumMillis = 0;
        int mttrCount = 0;
        Map<String, StageMetricsSnapshot> snapshot = new LinkedHashMap<>();

        for (Map.Entry<StageId, StageMetrics> e : perStage.entrySet()) {
            StageMetrics m = e.getValue();
            if (m.passed) {
                passed++;
            }
            totalRetries += m.retries.get();
            totalRollbacks += m.rollbacks.get();

            long firstFailureNanos = m.firstFailureNanos.get();
            Long mttrMillisForStage = null;
            if (firstFailureNanos != 0 && m.passed) {
                long recoveryNanos = endNanos - firstFailureNanos;
                mttrMillisForStage = recoveryNanos / 1_000_000;
                mttrSumMillis += mttrMillisForStage;
                mttrCount++;
            }

            snapshot.put(e.getKey().name(), new StageMetricsSnapshot(
                    m.attempts.get(), m.retries.get(), m.rollbacks.get(), m.passed,
                    m.totalDurationMillis.get(), mttrMillisForStage));
        }

        double successRate = total == 0 ? 0.0 : (double) passed / total;
        double retryFrequency = total == 0 ? 0.0 : (double) totalRetries / total;
        double rollbackFrequency = total == 0 ? 0.0 : (double) totalRollbacks / total;
        long mttrMillis = mttrCount == 0 ? 0 : mttrSumMillis / mttrCount;
        long endToEndLatencyMillis = (endNanos - runStartNanos) / 1_000_000;

        return new MetricsReport(total, passed, total - passed, round2(successRate), totalRetries,
                round2(retryFrequency), totalRollbacks, round2(rollbackFrequency), mttrMillis,
                endToEndLatencyMillis, snapshot);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static final class StageMetrics {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger retries = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();
        final AtomicLong totalDurationMillis = new AtomicLong();
        final AtomicLong firstFailureNanos = new AtomicLong();
        volatile boolean passed = false;
    }

    public record StageMetricsSnapshot(int attempts, int retries, int rollbacks, boolean passed,
                                        long totalDurationMillis, Long mttrMillis) {
    }
}
