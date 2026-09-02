package com.assessment.orchestrator.domain;

/** A documented, explicit clarifying assumption made while normalizing a requirement - never a silent guess. */
public record Assumption(String id, String statement, String rationale, String riskLevel) {
}
