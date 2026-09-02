package com.assessment.orchestrator.core;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;

/** One node in the workflow DAG: its dependencies, its agent, and its governance controls. */
public final class StageNode {

    private final StageId id;
    private final String displayName;
    private final Set<StageId> dependsOn;
    private final Agent agent;
    private final RetryPolicy retryPolicy;
    private final boolean requiresHumanApproval;
    private final Agent rollbackHandler;
    private final Function<ExecutionContext, GateResult> entryGate;
    private final Function<ExecutionContext, GateResult> exitGate;

    private StageNode(Builder b) {
        this.id = b.id;
        this.displayName = b.displayName;
        this.dependsOn = b.dependsOn;
        this.agent = b.agent;
        this.retryPolicy = b.retryPolicy;
        this.requiresHumanApproval = b.requiresHumanApproval;
        this.rollbackHandler = b.rollbackHandler;
        this.entryGate = b.entryGate;
        this.exitGate = b.exitGate;
    }

    public static Builder builder(StageId id, String displayName, Agent agent) {
        return new Builder(id, displayName, agent);
    }

    public StageId getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Set<StageId> getDependsOn() {
        return dependsOn;
    }

    public Agent getAgent() {
        return agent;
    }

    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    public boolean requiresHumanApproval() {
        return requiresHumanApproval;
    }

    public Agent getRollbackHandler() {
        return rollbackHandler;
    }

    public GateResult checkEntryGate(ExecutionContext ctx) {
        return entryGate == null ? GateResult.ok() : entryGate.apply(ctx);
    }

    public GateResult checkExitGate(ExecutionContext ctx) {
        return exitGate == null ? GateResult.ok() : exitGate.apply(ctx);
    }

    public static final class Builder {
        private final StageId id;
        private final String displayName;
        private final Agent agent;
        private Set<StageId> dependsOn = EnumSet.noneOf(StageId.class);
        private RetryPolicy retryPolicy = RetryPolicy.none();
        private boolean requiresHumanApproval = false;
        private Agent rollbackHandler;
        private Function<ExecutionContext, GateResult> entryGate;
        private Function<ExecutionContext, GateResult> exitGate;

        private Builder(StageId id, String displayName, Agent agent) {
            this.id = id;
            this.displayName = displayName;
            this.agent = agent;
        }

        public Builder dependsOn(StageId... ids) {
            this.dependsOn = ids.length == 0 ? EnumSet.noneOf(StageId.class) : EnumSet.copyOf(Set.of(ids));
            return this;
        }

        public Builder retryPolicy(RetryPolicy policy) {
            this.retryPolicy = policy;
            return this;
        }

        public Builder requiresHumanApproval(boolean value) {
            this.requiresHumanApproval = value;
            return this;
        }

        public Builder rollbackHandler(Agent handler) {
            this.rollbackHandler = handler;
            return this;
        }

        public Builder entryGate(Function<ExecutionContext, GateResult> gate) {
            this.entryGate = gate;
            return this;
        }

        public Builder exitGate(Function<ExecutionContext, GateResult> gate) {
            this.exitGate = gate;
            return this;
        }

        public StageNode build() {
            return new StageNode(this);
        }
    }
}
