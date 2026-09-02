package com.assessment.orchestrator.core;

import java.util.Optional;

/** A single governance/compliance/security check applied to a stage's outcome before it can pass. */
public interface PolicyRule {

    String name();

    /** Returns empty if the rule is satisfied, or a violation describing what failed. */
    Optional<PolicyViolation> evaluate(StageId stageId, ExecutionContext ctx, StageOutcome outcome);

    static PolicyRule of(String name, java.util.function.BiFunction<StageId, StageOutcome, Optional<PolicyViolation>> fn) {
        return new PolicyRule() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Optional<PolicyViolation> evaluate(StageId stageId, ExecutionContext ctx, StageOutcome outcome) {
                return fn.apply(stageId, outcome);
            }
        };
    }
}
