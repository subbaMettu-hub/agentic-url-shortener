package com.assessment.orchestrator.core;

/** A unit of simulated agent behavior that performs one SDLC stage's work. */
@FunctionalInterface
public interface Agent {
    StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber) throws Exception;
}
