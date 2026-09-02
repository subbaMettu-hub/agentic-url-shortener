package com.assessment.orchestrator.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** What an agent produced when it executed a stage. */
public final class StageOutcome {

    private final boolean success;
    private final String summary;
    private final Map<String, Object> artifacts;
    private final boolean requiresReplan;
    private final StageId replanTarget;
    private final String replanReason;

    private StageOutcome(boolean success, String summary, Map<String, Object> artifacts,
                          boolean requiresReplan, StageId replanTarget, String replanReason) {
        this.success = success;
        this.summary = summary;
        this.artifacts = artifacts;
        this.requiresReplan = requiresReplan;
        this.replanTarget = replanTarget;
        this.replanReason = replanReason;
    }

    public static Builder builder(String summary) {
        return new Builder(summary);
    }

    public static StageOutcome success(String summary) {
        return builder(summary).success(true).build();
    }

    public static StageOutcome failure(String summary) {
        return builder(summary).success(false).build();
    }

    public boolean isSuccess() {
        return success;
    }

    public String getSummary() {
        return summary;
    }

    public Map<String, Object> getArtifacts() {
        return artifacts;
    }

    public boolean requiresReplan() {
        return requiresReplan;
    }

    public Optional<StageId> getReplanTarget() {
        return Optional.ofNullable(replanTarget);
    }

    public String getReplanReason() {
        return replanReason;
    }

    public static final class Builder {
        private final String summary;
        private boolean success = true;
        private final Map<String, Object> artifacts = new HashMap<>();
        private boolean requiresReplan = false;
        private StageId replanTarget;
        private String replanReason;

        private Builder(String summary) {
            this.summary = summary;
        }

        public Builder success(boolean value) {
            this.success = value;
            return this;
        }

        public Builder artifact(String key, Object value) {
            this.artifacts.put(key, value);
            return this;
        }

        public Builder requiresReplan(StageId target, String reason) {
            this.requiresReplan = true;
            this.replanTarget = target;
            this.replanReason = reason;
            return this;
        }

        public StageOutcome build() {
            return new StageOutcome(success, summary, artifacts, requiresReplan, replanTarget, replanReason);
        }
    }
}
