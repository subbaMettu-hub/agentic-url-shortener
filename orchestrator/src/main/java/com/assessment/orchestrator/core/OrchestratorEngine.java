package com.assessment.orchestrator.core;

import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Non-linear, stateful DAG scheduler. One instance drives exactly one run.
 *
 * Scheduling loop: repeatedly computes the set of stages whose dependencies have all PASSED and
 * which are not currently in flight, submits them to a worker pool (so independent branches
 * genuinely execute in parallel), and blocks only until the next stage completes - not until the
 * whole batch does - so a fast branch can immediately unblock its own dependents without waiting
 * on a slower sibling branch. This is what gives "support sequential and parallel paths with
 * synchronization" real teeth rather than a fixed linear order with a cosmetic "parallel" label.
 */
public final class OrchestratorEngine {

    private static final int MAX_REPLANS_PER_RUN = 3;

    private final WorkflowGraph graph;
    private final PolicyEngine policyEngine;
    private final HumanApprovalPort approvalPort;
    private final AuditLogger audit;
    private final MetricsCollector metrics;
    private final ExecutorService pool;

    private final Map<StageId, StageStatus> stageStatus = new ConcurrentHashMap<>();
    private final AtomicBoolean safeStopRequested = new AtomicBoolean(false);
    private final AtomicInteger replanCount = new AtomicInteger(0);

    public OrchestratorEngine(WorkflowGraph graph, PolicyEngine policyEngine, HumanApprovalPort approvalPort,
                               AuditLogger audit, MetricsCollector metrics) {
        this.graph = graph;
        this.policyEngine = policyEngine;
        this.approvalPort = approvalPort;
        this.audit = audit;
        this.metrics = metrics;
        this.pool = Executors.newFixedThreadPool(Math.max(2, graph.allStages().size()));
        for (StageNode node : graph.allStages()) {
            stageStatus.put(node.getId(), StageStatus.PENDING);
        }
    }

    /** External, thread-safe safe-stop trigger: lets already-running stages finish but starts no new ones. */
    public void requestSafeStop(String reason) {
        if (safeStopRequested.compareAndSet(false, true)) {
            audit.log(new AuditEvent(java.time.Instant.now(), "-", 0, AuditEventType.SAFE_STOP, null,
                    "Safe-stop requested: " + reason, Map.of()));
        }
    }

    public RunResult run(ExecutionContext ctx, Duration timeoutBudget) {
        audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.RUN_START, null,
                "Run started for scenario '" + ctx.getScenarioName() + "'");

        long deadlineNanos = System.nanoTime() + timeoutBudget.toNanos();
        Map<StageId, Future<?>> inFlight = new ConcurrentHashMap<>();

        try {
            while (true) {
                if (System.nanoTime() > deadlineNanos) {
                    requestSafeStop("run exceeded timeout budget of " + timeoutBudget);
                }

                if (allPassed()) {
                    ctx.setStatus(RunStatus.COMPLETED);
                    break;
                }

                Set<StageId> ready = readyStages(inFlight.keySet());
                if (!safeStopRequested.get()) {
                    for (StageId id : ready) {
                        stageStatus.put(id, StageStatus.RUNNING);
                        StageNode node = graph.get(id);
                        Future<?> future = pool.submit(() -> executeStageWithRetries(ctx, node));
                        inFlight.put(id, future);
                    }
                }

                if (inFlight.isEmpty()) {
                    if (allPassed()) {
                        ctx.setStatus(RunStatus.COMPLETED);
                    } else if (hasTerminalFailure()) {
                        ctx.setStatus(RunStatus.FAILED);
                        audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.SAFE_STOP, null,
                                "No further stages can safely proceed - halting run. Stage statuses: " + stageStatus);
                    } else if (safeStopRequested.get()) {
                        ctx.setStatus(RunStatus.ABORTED);
                    } else {
                        ctx.setStatus(RunStatus.FAILED);
                        audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.SAFE_STOP, null,
                                "Scheduler stalled with no runnable stages and none in flight - halting.");
                    }
                    break;
                }

                waitForAnyCompletion(inFlight);
            }
        } finally {
            metrics.markRunEnd();
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }

        audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.RUN_END, null,
                "Run finished with status " + ctx.getStatus());

        return new RunResult(ctx.getRunId(), ctx.getStatus(), ctx, metrics.buildReport(),
                audit.getLogFile(), new EnumMap<>(stageStatus));
    }

    private void waitForAnyCompletion(Map<StageId, Future<?>> inFlight) {
        while (true) {
            for (Map.Entry<StageId, Future<?>> e : inFlight.entrySet()) {
                if (e.getValue().isDone()) {
                    inFlight.remove(e.getKey());
                    return;
                }
            }
            try {
                Thread.sleep(15);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean allPassed() {
        return stageStatus.values().stream().allMatch(s -> s == StageStatus.PASSED || s == StageStatus.SKIPPED);
    }

    private boolean hasTerminalFailure() {
        return stageStatus.values().stream().anyMatch(s ->
                s == StageStatus.FAILED || s == StageStatus.ROLLED_BACK || s == StageStatus.BLOCKED_BY_POLICY);
    }

    private Set<StageId> readyStages(Set<StageId> currentlyInFlight) {
        Set<StageId> ready = EnumSet.noneOf(StageId.class);
        for (StageNode node : graph.allStages()) {
            StageStatus status = stageStatus.get(node.getId());
            if ((status == StageStatus.PENDING || status == StageStatus.STALE)
                    && !currentlyInFlight.contains(node.getId())) {
                boolean depsSatisfied = node.getDependsOn().stream()
                        .allMatch(dep -> stageStatus.get(dep) == StageStatus.PASSED);
                if (depsSatisfied) {
                    ready.add(node.getId());
                }
            }
        }
        return ready;
    }

    private void executeStageWithRetries(ExecutionContext ctx, StageNode node) {
        StageId id = node.getId();

        GateResult entry = node.checkEntryGate(ctx);
        if (!entry.passed()) {
            stageStatus.put(id, StageStatus.BLOCKED_BY_POLICY);
            audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.STAGE_ENTRY_GATE_BLOCKED, id,
                    "Entry gate failed: " + entry.reasons());
            return;
        }

        RetryPolicy retryPolicy = node.getRetryPolicy();
        long startNanos = System.nanoTime();

        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            metrics.recordAttempt(id);
            stageStatus.put(id, attempt == 1 ? StageStatus.RUNNING : StageStatus.RETRYING);
            audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.STAGE_START, id,
                    "Attempt " + attempt + "/" + retryPolicy.maxAttempts() + " for " + node.getDisplayName());

            StageOutcome outcome;
            try {
                outcome = node.getAgent().execute(ctx, node, attempt);
            } catch (Exception e) {
                outcome = StageOutcome.failure("Agent threw exception: " + e.getMessage());
            }

            List<PolicyViolation> violations = policyEngine.evaluate(id, ctx, outcome);
            for (PolicyViolation v : violations) {
                audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.POLICY_VIOLATION, id,
                        "[" + v.severity() + "] " + v.ruleName() + ": " + v.message());
            }
            boolean blockedByPolicy = violations.stream().anyMatch(v -> v.severity() == PolicySeverity.BLOCK);

            boolean effectiveSuccess = outcome.isSuccess() && !blockedByPolicy;
            if (effectiveSuccess) {
                GateResult exit = node.checkExitGate(ctx);
                if (!exit.passed()) {
                    effectiveSuccess = false;
                    audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.STAGE_EXIT_GATE_BLOCKED, id,
                            "Exit gate failed: " + exit.reasons());
                }
            }

            boolean humanRejected = false;
            if (effectiveSuccess && node.requiresHumanApproval()) {
                audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.APPROVAL_REQUESTED, id,
                        "Requesting human approval: " + outcome.getSummary());
                ApprovalDecision decision = approvalPort.requestApproval(ctx, node, outcome);
                audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.APPROVAL_DECISION, id,
                        (decision.approved() ? "APPROVED" : "REJECTED") + " by " + decision.approver()
                                + " - " + decision.comment());
                ctx.recordDecision(decision.approver(), id, decision.approved() ? "APPROVED" : "REJECTED",
                        decision.comment());
                if (!decision.approved()) {
                    effectiveSuccess = false;
                    humanRejected = true;
                }
            }

            if (effectiveSuccess) {
                outcome.getArtifacts().forEach(ctx::putArtifact);
                long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
                metrics.recordPass(id, durationMillis);
                stageStatus.put(id, StageStatus.PASSED);
                audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.STAGE_PASS, id, outcome.getSummary());

                if (outcome.requiresReplan()) {
                    handleReplan(ctx, id, outcome);
                }
                return;
            }

            metrics.recordFailure(id);
            audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.STAGE_FAIL, id,
                    "Attempt " + attempt + " failed: " + outcome.getSummary());

            boolean canRetry = attempt < retryPolicy.maxAttempts() && !humanRejected && !blockedByPolicy;
            if (canRetry) {
                metrics.recordRetry(id);
                audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.STAGE_RETRY, id,
                        "Retrying after " + retryPolicy.backoffMillis() + "ms backoff");
                sleep(retryPolicy.backoffMillis());
            } else {
                stageStatus.put(id, StageStatus.FAILED);
                attemptRollback(ctx, node);
                return;
            }
        }
    }

    private void attemptRollback(ExecutionContext ctx, StageNode node) {
        if (node.getRollbackHandler() == null) {
            return;
        }
        try {
            StageOutcome rollbackOutcome = node.getRollbackHandler().execute(ctx, node, 0);
            metrics.recordRollback(node.getId());
            stageStatus.put(node.getId(), StageStatus.ROLLED_BACK);
            audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.ROLLBACK, node.getId(),
                    rollbackOutcome.getSummary());
        } catch (Exception e) {
            audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.ROLLBACK, node.getId(),
                    "Rollback handler itself failed: " + e.getMessage());
        }
    }

    private void handleReplan(ExecutionContext ctx, StageId triggeringStage, StageOutcome outcome) {
        StageId target = outcome.getReplanTarget().orElseThrow();
        if (replanCount.incrementAndGet() > MAX_REPLANS_PER_RUN) {
            audit.log(ctx.getRunId(), ctx.getPlanVersion(), AuditEventType.SAFE_STOP, triggeringStage,
                    "Replan budget exhausted (" + MAX_REPLANS_PER_RUN + ") - forcing safe-stop to avoid a re-plan loop");
            requestSafeStop("replan budget exhausted");
            return;
        }

        int newPlanVersion = ctx.bumpPlanVersion();
        ctx.recordDecision("orchestrator-engine", triggeringStage, "REPLAN_TRIGGERED", outcome.getReplanReason());
        audit.log(ctx.getRunId(), newPlanVersion, AuditEventType.REPLAN, triggeringStage,
                "Re-entering " + target + " (plan v" + newPlanVersion + "): " + outcome.getReplanReason());

        Set<StageId> invalidated = EnumSet.of(target);
        invalidated.addAll(graph.transitiveDependents(target));
        for (StageId id : invalidated) {
            stageStatus.put(id, StageStatus.STALE);
            audit.log(ctx.getRunId(), newPlanVersion, AuditEventType.STAGE_STALE, id,
                    "Invalidated by re-plan originating at " + triggeringStage);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
