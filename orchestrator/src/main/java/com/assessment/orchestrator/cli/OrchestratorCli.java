package com.assessment.orchestrator.cli;

import com.assessment.orchestrator.core.*;
import com.assessment.orchestrator.policy.StandardPolicies;
import com.assessment.orchestrator.scenarios.ScenarioCatalog;
import com.assessment.orchestrator.scenarios.ScenarioDefinition;
import com.assessment.orchestrator.workflow.SdlcWorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Entry point: runs one, several, or all three SDLC scenarios end to end through the
 * orchestration engine, writing every artifact (requirements/design/analysis/test/docs/release
 * reports, audit.jsonl, metrics.json, report.md) under {@code orchestrator/runs/<scenario>/<runId>/}.
 *
 * Usage:
 *   mvn -f orchestrator/pom.xml exec:java -Dexec.args="--scenario=all"
 *   java -jar orchestrator/target/orchestrator.jar --scenario=brownfield --interactive
 *   java -jar orchestrator/target/orchestrator.jar --scenario=greenfield --inject-failure=STATIC_ANALYSIS
 */
public final class OrchestratorCli {

    public static void main(String[] args) {
        Map<String, String> opts = parseArgs(args);

        Path projectRoot = ProjectPaths.resolveProjectRoot(
                opts.containsKey("project-root") ? Path.of(opts.get("project-root")) : null);
        boolean interactive = opts.containsKey("interactive");
        long timeoutSeconds = Long.parseLong(opts.getOrDefault("timeout-seconds", "180"));
        Set<StageId> injectFailure = opts.containsKey("inject-failure")
                ? EnumSet.of(StageId.valueOf(opts.get("inject-failure")))
                : SdlcWorkflowDefinition.noFailureInjection();

        List<ScenarioDefinition> toRun = new ArrayList<>();
        String scenarioArg = opts.getOrDefault("scenario", "all");
        if (scenarioArg.equals("all")) {
            toRun.addAll(ScenarioCatalog.all().values());
        } else {
            for (String key : scenarioArg.split(",")) {
                toRun.add(ScenarioCatalog.byKey(key.trim()));
            }
        }

        System.out.println("Project root: " + projectRoot);
        System.out.println("Scenarios to run: " + toRun.stream().map(ScenarioDefinition::key).toList());
        if (!injectFailure.isEmpty()) {
            System.out.println("!! Failure injection ACTIVE for stage(s): " + injectFailure);
        }

        int exitCode = 0;
        for (ScenarioDefinition scenario : toRun) {
            RunResult result = runScenario(scenario, projectRoot, interactive, timeoutSeconds, injectFailure);
            if (result.status() != RunStatus.COMPLETED) {
                exitCode = 1;
            }
        }
        System.exit(exitCode);
    }

    private static RunResult runScenario(ScenarioDefinition scenario, Path projectRoot, boolean interactive,
                                          long timeoutSeconds, Set<StageId> injectFailure) {
        String runId = scenario.key() + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(
                java.time.LocalDateTime.now());
        Path runOutputDir = ProjectPaths.runsOutputDir(projectRoot).resolve(scenario.key()).resolve(runId);

        Map<String, Object> config = new HashMap<>(scenario.extraConfig());
        config.put("urlShortenerModuleDir", ProjectPaths.urlShortenerModuleDir(projectRoot));
        config.put("changelogFile", ProjectPaths.changelogFile(projectRoot));

        ExecutionContext ctx = new ExecutionContext(runId, scenario.displayName(), scenario.rawRequirement(),
                runOutputDir, ProjectPaths.urlShortenerSrcMainJava(projectRoot), config);

        WorkflowGraph graph = SdlcWorkflowDefinition.build(scenario.implementationAgent(), injectFailure);
        AuditLogger audit = new AuditLogger(runOutputDir, true);
        MetricsCollector metricsCollector = new MetricsCollector();
        HumanApprovalPort approvalPort = interactive ? new InteractiveConsoleApprovalPort() : new AutoApprovalPort();

        System.out.println();
        System.out.println("================================================================");
        System.out.println("SCENARIO: " + scenario.displayName());
        System.out.println("Raw requirement: " + scenario.rawRequirement());
        System.out.println("Run ID: " + runId);
        System.out.println("Output: " + runOutputDir);
        System.out.println("================================================================");

        OrchestratorEngine engine = new OrchestratorEngine(graph, StandardPolicies.standard(), approvalPort, audit, metricsCollector);
        RunResult result = engine.run(ctx, Duration.ofSeconds(timeoutSeconds));

        writeMetricsJson(runOutputDir, result);
        writeSummaryReport(runOutputDir, result, scenario);

        System.out.println();
        System.out.println(">> Scenario '" + scenario.key() + "' finished with status " + result.status());
        System.out.printf(">> successRate=%.2f retries=%d rollbacks=%d mttrMs=%d e2eLatencyMs=%d%n",
                result.metrics().successRate(), result.metrics().totalRetries(), result.metrics().totalRollbacks(),
                result.metrics().mttrMillis(), result.metrics().endToEndLatencyMillis());
        System.out.println(">> Full report: " + runOutputDir.resolve("report.md"));
        System.out.println(">> Audit log:   " + result.auditLogPath());

        return result;
    }

    private static void writeMetricsJson(Path runOutputDir, RunResult result) {
        try {
            ObjectMapper mapper = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(runOutputDir.resolve("metrics.json").toFile(), result.metrics());
        } catch (Exception e) {
            System.err.println("Failed to write metrics.json: " + e.getMessage());
        }
    }

    private static void writeSummaryReport(Path runOutputDir, RunResult result, ScenarioDefinition scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Run Report - ").append(scenario.displayName()).append("\n\n");
        sb.append("- Run ID: ").append(result.runId()).append("\n");
        sb.append("- Final status: ").append(result.status()).append("\n");
        sb.append("- Plan version reached: ").append(result.context().getPlanVersion()).append("\n\n");

        sb.append("## Final stage statuses\n\n| Stage | Status |\n|---|---|\n");
        result.finalStageStatuses().forEach((id, status) -> sb.append("| ").append(id).append(" | ").append(status).append(" |\n"));

        sb.append("\n## Reliability metrics\n\n");
        MetricsReport m = result.metrics();
        sb.append("| Metric | Value |\n|---|---|\n");
        sb.append("| Success rate | ").append(m.successRate()).append(" |\n");
        sb.append("| Total retries | ").append(m.totalRetries()).append(" |\n");
        sb.append("| Retry frequency (per stage) | ").append(m.retryFrequency()).append(" |\n");
        sb.append("| Total rollbacks | ").append(m.totalRollbacks()).append(" |\n");
        sb.append("| Rollback frequency (per stage) | ").append(m.rollbackFrequency()).append(" |\n");
        sb.append("| MTTR (ms) | ").append(m.mttrMillis()).append(" |\n");
        sb.append("| End-to-end latency (ms) | ").append(m.endToEndLatencyMillis()).append(" |\n");

        sb.append("\n## Decision lineage\n\n");
        for (DecisionRecord d : result.context().getDecisionLineage()) {
            sb.append("- `").append(d.timestamp()).append("` [plan v").append(d.planVersion()).append("] **")
                    .append(d.actor()).append("** @ ").append(d.stageId()).append(" -> ").append(d.decision())
                    .append(": ").append(d.rationale()).append("\n");
        }

        sb.append("\n## Stage-level metrics\n\n| Stage | Attempts | Retries | Rollbacks | Passed | MTTR (ms) |\n|---|---|---|---|---|---|\n");
        m.perStage().forEach((stage, s) -> sb.append("| ").append(stage).append(" | ").append(s.attempts())
                .append(" | ").append(s.retries()).append(" | ").append(s.rollbacks()).append(" | ")
                .append(s.passed()).append(" | ").append(s.mttrMillis() == null ? "-" : s.mttrMillis()).append(" |\n"));

        sb.append("\nGenerated ").append(Instant.now()).append(". See audit.jsonl in this directory for the full, "
                + "machine-readable event stream this report is derived from.\n");

        com.assessment.orchestrator.util.ArtifactWriter.write(runOutputDir, "report.md", sb.toString());
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            String body = arg.substring(2);
            int eq = body.indexOf('=');
            if (eq >= 0) {
                opts.put(body.substring(0, eq), body.substring(eq + 1));
            } else {
                opts.put(body, "true");
            }
        }
        return opts;
    }
}
