package com.assessment.orchestrator.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorEngineTest {

    private ExecutionContext newContext(Path tmp, String runId) {
        return new ExecutionContext(runId, "unit-test", "synthetic requirement", tmp, tmp, Map.of());
    }

    private OrchestratorEngine newEngine(WorkflowGraph graph) {
        return new OrchestratorEngine(graph, new PolicyEngine(), new AutoApprovalPort(),
                new AuditLogger(Path.of("target", "test-audit-" + System.nanoTime()), false), new MetricsCollector());
    }

    @Test
    void executesDiamondDagRespectingDependencyOrder(@TempDir Path tmp) {
        List<StageId> executionOrder = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        Agent recordingAgent = (ctx, self, attempt) -> {
            executionOrder.add(self.getId());
            return StageOutcome.success(self.getId() + " done");
        };

        WorkflowGraph graph = WorkflowGraph.builder()
                .addStage(StageNode.builder(StageId.REQUIREMENTS, "Requirements", recordingAgent).build())
                .addStage(StageNode.builder(StageId.DESIGN_API, "Design API", recordingAgent)
                        .dependsOn(StageId.REQUIREMENTS).build())
                .addStage(StageNode.builder(StageId.DESIGN_DATA_MODEL, "Design Data Model", recordingAgent)
                        .dependsOn(StageId.REQUIREMENTS).build())
                .addStage(StageNode.builder(StageId.IMPLEMENTATION, "Implementation", recordingAgent)
                        .dependsOn(StageId.DESIGN_API, StageId.DESIGN_DATA_MODEL).build())
                .build();

        RunResult result = newEngine(graph).run(newContext(tmp, "diamond"), Duration.ofSeconds(30));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(executionOrder).hasSize(4);
        assertThat(executionOrder.indexOf(StageId.REQUIREMENTS)).isLessThan(executionOrder.indexOf(StageId.DESIGN_API));
        assertThat(executionOrder.indexOf(StageId.REQUIREMENTS)).isLessThan(executionOrder.indexOf(StageId.DESIGN_DATA_MODEL));
        assertThat(executionOrder.indexOf(StageId.DESIGN_API)).isLessThan(executionOrder.indexOf(StageId.IMPLEMENTATION));
        assertThat(executionOrder.indexOf(StageId.DESIGN_DATA_MODEL)).isLessThan(executionOrder.indexOf(StageId.IMPLEMENTATION));
    }

    @Test
    void retriesUpToBoundThenPasses(@TempDir Path tmp) {
        AtomicInteger calls = new AtomicInteger(0);
        Agent flakyAgent = (ctx, self, attempt) -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                return StageOutcome.failure("transient failure #" + n);
            }
            return StageOutcome.success("succeeded on attempt " + n);
        };

        WorkflowGraph graph = WorkflowGraph.builder()
                .addStage(StageNode.builder(StageId.REQUIREMENTS, "Requirements", flakyAgent)
                        .retryPolicy(RetryPolicy.of(5, 1)).build())
                .build();

        RunResult result = newEngine(graph).run(newContext(tmp, "retry"), Duration.ofSeconds(30));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(calls.get()).isEqualTo(3);
        assertThat(result.metrics().totalRetries()).isEqualTo(2);
        assertThat(result.metrics().successRate()).isEqualTo(1.0);
    }

    @Test
    void exhaustsRetriesTriggersRollbackAndFailsRun(@TempDir Path tmp) {
        AtomicInteger rollbackCalls = new AtomicInteger(0);
        Agent alwaysFails = (ctx, self, attempt) -> StageOutcome.failure("permanent failure");
        Agent rollback = (ctx, self, attempt) -> {
            rollbackCalls.incrementAndGet();
            return StageOutcome.success("rolled back cleanly");
        };

        WorkflowGraph graph = WorkflowGraph.builder()
                .addStage(StageNode.builder(StageId.REQUIREMENTS, "Requirements", alwaysFails)
                        .retryPolicy(RetryPolicy.of(2, 1))
                        .rollbackHandler(rollback)
                        .build())
                .addStage(StageNode.builder(StageId.DESIGN_API, "Design API", (ctx, self, attempt) -> StageOutcome.success("n/a"))
                        .dependsOn(StageId.REQUIREMENTS).build())
                .build();

        RunResult result = newEngine(graph).run(newContext(tmp, "rollback"), Duration.ofSeconds(30));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(rollbackCalls.get()).isEqualTo(1);
        assertThat(result.finalStageStatuses().get(StageId.REQUIREMENTS)).isEqualTo(StageStatus.ROLLED_BACK);
        assertThat(result.finalStageStatuses().get(StageId.DESIGN_API)).isEqualTo(StageStatus.PENDING);
        assertThat(result.metrics().totalRollbacks()).isEqualTo(1);
    }

    @Test
    void blockingPolicyViolationFailsStageWithoutRetry(@TempDir Path tmp) {
        AtomicInteger calls = new AtomicInteger(0);
        Agent agent = (ctx, self, attempt) -> {
            calls.incrementAndGet();
            return StageOutcome.builder("looks fine to the agent").artifact("secret", "password=hunter2").build();
        };

        PolicyEngine policy = new PolicyEngine().register(PolicyRule.of("no-secrets", (stageId, outcome) ->
                outcome.getArtifacts().values().stream().anyMatch(v -> String.valueOf(v).contains("password="))
                        ? java.util.Optional.of(new PolicyViolation("no-secrets", PolicySeverity.BLOCK, "secret detected"))
                        : java.util.Optional.empty()));

        WorkflowGraph graph = WorkflowGraph.builder()
                .addStage(StageNode.builder(StageId.REQUIREMENTS, "Requirements", agent)
                        .retryPolicy(RetryPolicy.of(3, 1)).build())
                .build();

        OrchestratorEngine engine = new OrchestratorEngine(graph, policy, new AutoApprovalPort(),
                new AuditLogger(tmp.resolve("audit"), false), new MetricsCollector());

        RunResult result = engine.run(newContext(tmp, "policy"), Duration.ofSeconds(30));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.finalStageStatuses().get(StageId.REQUIREMENTS)).isEqualTo(StageStatus.FAILED);
    }

    @Test
    void humanRejectionFailsStageWithoutRetry(@TempDir Path tmp) {
        AtomicInteger calls = new AtomicInteger(0);
        Agent agent = (ctx, self, attempt) -> {
            calls.incrementAndGet();
            return StageOutcome.success("ready for review");
        };
        HumanApprovalPort alwaysReject = (ctx, stage, outcome) -> ApprovalDecision.reject("test-human", "no");

        WorkflowGraph graph = WorkflowGraph.builder()
                .addStage(StageNode.builder(StageId.RELEASE_READINESS, "Release Readiness", agent)
                        .retryPolicy(RetryPolicy.of(3, 1))
                        .requiresHumanApproval(true)
                        .build())
                .build();

        OrchestratorEngine engine = new OrchestratorEngine(graph, new PolicyEngine(), alwaysReject,
                new AuditLogger(tmp.resolve("audit"), false), new MetricsCollector());

        RunResult result = engine.run(newContext(tmp, "reject"), Duration.ofSeconds(30));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void replanInvalidatesDownstreamAndRerunsThem(@TempDir Path tmp) {
        AtomicInteger requirementsCalls = new AtomicInteger(0);
        AtomicInteger designCalls = new AtomicInteger(0);
        AtomicInteger implCalls = new AtomicInteger(0);

        Agent requirementsAgent = (ctx, self, attempt) -> {
            requirementsCalls.incrementAndGet();
            return StageOutcome.success("requirements v" + requirementsCalls.get());
        };
        Agent designAgent = (ctx, self, attempt) -> {
            int n = designCalls.incrementAndGet();
            if (n == 1) {
                return StageOutcome.builder("initial assumption insufficient")
                        .requiresReplan(StageId.REQUIREMENTS, "discovered missing constraint during design")
                        .build();
            }
            return StageOutcome.success("design finalized on pass " + n);
        };
        Agent implAgent = (ctx, self, attempt) -> {
            implCalls.incrementAndGet();
            return StageOutcome.success("implemented");
        };

        WorkflowGraph graph = WorkflowGraph.builder()
                .addStage(StageNode.builder(StageId.REQUIREMENTS, "Requirements", requirementsAgent).build())
                .addStage(StageNode.builder(StageId.DESIGN_API, "Design", designAgent)
                        .dependsOn(StageId.REQUIREMENTS).build())
                .addStage(StageNode.builder(StageId.IMPLEMENTATION, "Implementation", implAgent)
                        .dependsOn(StageId.DESIGN_API).build())
                .build();

        RunResult result = newEngine(graph).run(newContext(tmp, "replan"), Duration.ofSeconds(30));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(requirementsCalls.get()).isEqualTo(2);
        assertThat(designCalls.get()).isEqualTo(2);
        assertThat(implCalls.get()).isEqualTo(1);
        assertThat(result.context().getPlanVersion()).isEqualTo(2);
        assertThat(result.context().getDecisionLineage())
                .anyMatch(d -> d.decision().equals("REPLAN_TRIGGERED"));
    }

    @Test
    void detectsCyclicGraphAtConstructionTime() {
        Agent noop = (ctx, self, attempt) -> StageOutcome.success("noop");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                WorkflowGraph.builder()
                        .addStage(StageNode.builder(StageId.REQUIREMENTS, "A", noop).dependsOn(StageId.DESIGN_API).build())
                        .addStage(StageNode.builder(StageId.DESIGN_API, "B", noop).dependsOn(StageId.REQUIREMENTS).build())
                        .build());
    }

    @Test
    void safeStopPreventsNewStagesFromStarting(@TempDir Path tmp) {
        WorkflowGraph graph = WorkflowGraph.builder()
                .addStage(StageNode.builder(StageId.REQUIREMENTS, "Requirements",
                        (ctx, self, attempt) -> StageOutcome.success("ok")).build())
                .build();

        OrchestratorEngine engine = newEngine(graph);
        engine.requestSafeStop("manual test trigger");

        RunResult result = engine.run(newContext(tmp, "safe-stop"), Duration.ofSeconds(5));

        assertThat(result.status()).isEqualTo(RunStatus.ABORTED);
        assertThat(result.finalStageStatuses().get(StageId.REQUIREMENTS)).isEqualTo(StageStatus.PENDING);
    }
}
