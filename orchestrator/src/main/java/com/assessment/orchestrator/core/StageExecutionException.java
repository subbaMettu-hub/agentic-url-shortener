package com.assessment.orchestrator.core;

public class StageExecutionException extends RuntimeException {
    public StageExecutionException(String message) {
        super(message);
    }

    public StageExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
