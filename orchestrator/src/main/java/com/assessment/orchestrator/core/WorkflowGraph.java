package com.assessment.orchestrator.core;

import java.util.*;

/** An explicit dependency graph over {@link StageNode}s. Validated to be acyclic at construction. */
public final class WorkflowGraph {

    private final Map<StageId, StageNode> nodes;

    private WorkflowGraph(Map<StageId, StageNode> nodes) {
        this.nodes = nodes;
        validateDependenciesExist();
        validateAcyclic();
    }

    public static Builder builder() {
        return new Builder();
    }

    public StageNode get(StageId id) {
        StageNode node = nodes.get(id);
        if (node == null) {
            throw new NoSuchElementException("No such stage: " + id);
        }
        return node;
    }

    public Collection<StageNode> allStages() {
        return nodes.values();
    }

    /** Stages that transitively depend on {@code changed} (used to invalidate downstream work on re-plan). */
    public Set<StageId> transitiveDependents(StageId changed) {
        Set<StageId> result = EnumSet.noneOf(StageId.class);
        boolean progress = true;
        while (progress) {
            progress = false;
            for (StageNode node : nodes.values()) {
                if (result.contains(node.getId())) {
                    continue;
                }
                boolean dependsOnAffected = node.getDependsOn().contains(changed) || node.getDependsOn().stream().anyMatch(result::contains);
                if (dependsOnAffected) {
                    result.add(node.getId());
                    progress = true;
                }
            }
        }
        return result;
    }

    private void validateDependenciesExist() {
        for (StageNode node : nodes.values()) {
            for (StageId dep : node.getDependsOn()) {
                if (!nodes.containsKey(dep)) {
                    throw new IllegalStateException("Stage " + node.getId() + " depends on undefined stage " + dep);
                }
            }
        }
    }

    private void validateAcyclic() {
        Set<StageId> visited = EnumSet.noneOf(StageId.class);
        Set<StageId> inStack = EnumSet.noneOf(StageId.class);
        for (StageId id : nodes.keySet()) {
            if (!visited.contains(id) && hasCycle(id, visited, inStack)) {
                throw new IllegalStateException("Workflow graph contains a cycle involving " + id);
            }
        }
    }

    private boolean hasCycle(StageId id, Set<StageId> visited, Set<StageId> inStack) {
        visited.add(id);
        inStack.add(id);
        for (StageId dep : nodes.get(id).getDependsOn()) {
            if (inStack.contains(dep)) {
                return true;
            }
            if (!visited.contains(dep) && hasCycle(dep, visited, inStack)) {
                return true;
            }
        }
        inStack.remove(id);
        return false;
    }

    public static final class Builder {
        private final Map<StageId, StageNode> nodes = new LinkedHashMap<>();

        public Builder addStage(StageNode node) {
            nodes.put(node.getId(), node);
            return this;
        }

        public WorkflowGraph build() {
            return new WorkflowGraph(new LinkedHashMap<>(nodes));
        }
    }
}
