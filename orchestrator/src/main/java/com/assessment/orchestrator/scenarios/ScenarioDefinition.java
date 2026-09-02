package com.assessment.orchestrator.scenarios;

import com.assessment.orchestrator.core.Agent;

import java.util.Map;

public record ScenarioDefinition(
        String key,
        String displayName,
        String rawRequirement,
        Agent implementationAgent,
        Map<String, Object> extraConfig
) {
}
