package com.assessment.orchestrator.llm;

import java.util.Optional;

/**
 * Abstraction over "ask a real model to reason about something." Lets an agent optionally use
 * genuine LLM reasoning without hard-depending on network access or a specific provider.
 *
 * Implementations must never throw: unavailability (no credentials configured) or any failure
 * (network error, timeout, non-2xx response, malformed output) is reported as
 * {@code Optional.empty()} / {@code isAvailable() == false}, never an exception. This is what
 * lets every caller keep an unconditional deterministic fallback and still guarantee the pipeline
 * completes even with no key configured - see {@code ClaudeReasoningProvider} and how
 * {@code RequirementsAgent} consumes this interface.
 */
public interface ReasoningProvider {

    /** Whether this provider is configured and able to attempt a call (e.g. an API key is set). */
    boolean isAvailable();

    /**
     * Asks the model to respond to {@code userPrompt} under {@code systemPrompt}. Returns the raw
     * text response, or empty if the provider is unavailable or the call failed for any reason.
     */
    Optional<String> complete(String systemPrompt, String userPrompt);
}
