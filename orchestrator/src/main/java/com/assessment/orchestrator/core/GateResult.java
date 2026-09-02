package com.assessment.orchestrator.core;

import java.util.List;

/** Result of an entry or exit gate check on a stage. */
public record GateResult(boolean passed, List<String> reasons) {

    public static GateResult ok() {
        return new GateResult(true, List.of());
    }

    public static GateResult fail(String... reasons) {
        return new GateResult(false, List.of(reasons));
    }
}
