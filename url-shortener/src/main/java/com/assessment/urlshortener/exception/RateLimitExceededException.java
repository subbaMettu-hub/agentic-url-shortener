package com.assessment.urlshortener.exception;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String clientKey) {
        super("Rate limit exceeded for client: " + clientKey);
    }
}
