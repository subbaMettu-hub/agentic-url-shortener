package com.assessment.orchestrator.core;

/** Bounded retry configuration for a stage: at most maxAttempts total tries, with linear backoff. */
public record RetryPolicy(int maxAttempts, long backoffMillis) {

    public static RetryPolicy none() {
        return new RetryPolicy(1, 0);
    }

    public static RetryPolicy of(int maxAttempts, long backoffMillis) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        return new RetryPolicy(maxAttempts, backoffMillis);
    }
}
