package com.assessment.orchestrator.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Central governance layer: every stage outcome is run through the same set of policy rules
 * before it can be accepted, regardless of which agent produced it. A BLOCK-severity violation
 * fails the stage outright (governance overrides whatever the agent itself reported); WARN
 * violations are recorded in the audit trail and surfaced in the release readiness report but do
 * not stop the run.
 */
public final class PolicyEngine {

    private final List<PolicyRule> rules = new ArrayList<>();

    public PolicyEngine register(PolicyRule rule) {
        rules.add(rule);
        return this;
    }

    public List<PolicyViolation> evaluate(StageId stageId, ExecutionContext ctx, StageOutcome outcome) {
        List<PolicyViolation> violations = new ArrayList<>();
        for (PolicyRule rule : rules) {
            rule.evaluate(stageId, ctx, outcome).ifPresent(violations::add);
        }
        return violations;
    }

    public List<PolicyRule> getRules() {
        return List.copyOf(rules);
    }
}
