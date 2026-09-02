package com.assessment.orchestrator.scenarios;

import com.assessment.orchestrator.agents.AmbiguousImplementationAgent;
import com.assessment.orchestrator.agents.BrownfieldImplementationAgent;
import com.assessment.orchestrator.agents.GreenfieldImplementationAgent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/** The three demonstration scenarios required by the assessment: greenfield, brownfield, ambiguous. */
public final class ScenarioCatalog {

    private ScenarioCatalog() {
    }

    public static ScenarioDefinition greenfield() {
        return new ScenarioDefinition(
                "greenfield",
                "Greenfield: QR code generation for short URLs",
                "Add the ability for users to generate a QR code image for any shortened URL, "
                        + "accessible via a new endpoint, so links can be shared in print/offline contexts.",
                new GreenfieldImplementationAgent(),
                Map.of("staticAnalysisFlakyAttempts", 0)
        );
    }

    public static ScenarioDefinition brownfield() {
        return new ScenarioDefinition(
                "brownfield",
                "Brownfield: fix click-count race condition + add redirect caching",
                "The click analytics endpoint under-counts clicks under concurrent redirects to the same "
                        + "short code (a classic read-modify-write race), and hot redirects are hitting the "
                        + "database on every request. Fix the click counting bug and add a caching layer for "
                        + "hot redirects to reduce DB load.",
                new BrownfieldImplementationAgent(),
                Map.of("staticAnalysisFlakyAttempts", 1)
        );
    }

    public static ScenarioDefinition ambiguous() {
        return new ScenarioDefinition(
                "ambiguous",
                "Ambiguous: \"make the URL shortener more reliable\"",
                "Make the URL shortener more reliable.",
                new AmbiguousImplementationAgent(),
                Map.of("staticAnalysisFlakyAttempts", 0)
        );
    }

    public static Map<String, ScenarioDefinition> all() {
        Map<String, ScenarioDefinition> map = new LinkedHashMap<>();
        for (ScenarioDefinition s : new ScenarioDefinition[]{greenfield(), brownfield(), ambiguous()}) {
            map.put(s.key(), s);
        }
        return map;
    }

    public static ScenarioDefinition byKey(String key) {
        ScenarioDefinition s = all().get(key);
        if (s == null) {
            throw new NoSuchElementException("Unknown scenario '" + key + "'. Valid: " + all().keySet());
        }
        return s;
    }
}
