package com.assessment.orchestrator.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cross-stage state for a single workflow run. Shared by every (possibly concurrently running)
 * stage, so every mutable field is a concurrent-safe structure. This is what "preserves
 * cross-stage context and decision lineage" across the run: agents read prior stages' artifacts
 * out of {@link #artifacts} and append to {@link #decisionLineage} as they make judgment calls.
 */
public final class ExecutionContext {

    private final String runId;
    private final String scenarioName;
    private final String rawRequirement;
    private final Path runOutputDir;
    private final Path repoRoot;

    private final Map<String, Object> artifacts = new ConcurrentHashMap<>();
    private final List<DecisionRecord> decisionLineage = new CopyOnWriteArrayList<>();
    private final AtomicReference<RunStatus> status = new AtomicReference<>(RunStatus.RUNNING);
    private final AtomicInteger planVersion = new AtomicInteger(1);
    private final Map<String, Object> extraConfig;

    public ExecutionContext(String runId, String scenarioName, String rawRequirement,
                             Path runOutputDir, Path repoRoot, Map<String, Object> extraConfig) {
        this.runId = runId;
        this.scenarioName = scenarioName;
        this.rawRequirement = rawRequirement;
        this.runOutputDir = runOutputDir;
        this.repoRoot = repoRoot;
        this.extraConfig = extraConfig == null ? Map.of() : extraConfig;
    }

    public String getRunId() {
        return runId;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public String getRawRequirement() {
        return rawRequirement;
    }

    public Path getRunOutputDir() {
        return runOutputDir;
    }

    public Path getRepoRoot() {
        return repoRoot;
    }

    public Object getConfig(String key) {
        return extraConfig.get(key);
    }

    public void putArtifact(String key, Object value) {
        artifacts.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getArtifact(String key) {
        return (T) artifacts.get(key);
    }

    public boolean hasArtifact(String key) {
        return artifacts.containsKey(key);
    }

    public Map<String, Object> getAllArtifacts() {
        return Map.copyOf(artifacts);
    }

    public void recordDecision(DecisionRecord record) {
        decisionLineage.add(record);
    }

    public void recordDecision(String actor, StageId stageId, String decision, String rationale) {
        decisionLineage.add(DecisionRecord.of(actor, stageId, decision, rationale, planVersion.get()));
    }

    public List<DecisionRecord> getDecisionLineage() {
        return List.copyOf(decisionLineage);
    }

    public RunStatus getStatus() {
        return status.get();
    }

    public void setStatus(RunStatus newStatus) {
        status.set(newStatus);
    }

    public boolean compareAndSetStatus(RunStatus expected, RunStatus update) {
        return status.compareAndSet(expected, update);
    }

    public int getPlanVersion() {
        return planVersion.get();
    }

    public int bumpPlanVersion() {
        return planVersion.incrementAndGet();
    }
}
