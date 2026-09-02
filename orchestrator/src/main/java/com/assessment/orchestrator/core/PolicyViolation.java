package com.assessment.orchestrator.core;

public record PolicyViolation(String ruleName, PolicySeverity severity, String message) {
}
