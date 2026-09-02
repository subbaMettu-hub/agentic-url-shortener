package com.assessment.orchestrator.domain;

import java.util.List;

/** The normalized, engineering-ready form of a raw natural-language requirement. */
public record RequirementSpec(
        String rawRequirement,
        String normalizedGoal,
        List<String> inScope,
        List<String> outOfScope,
        List<String> acceptanceCriteria,
        List<Assumption> assumptions,
        List<String> openQuestions,
        boolean ambiguous,
        int revision
) {
}
