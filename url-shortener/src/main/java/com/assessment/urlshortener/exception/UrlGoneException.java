package com.assessment.urlshortener.exception;

/** Thrown when a short code resolves to a URL that has expired or been deactivated. */
public class UrlGoneException extends RuntimeException {
    public UrlGoneException(String shortCode) {
        super("Short URL is no longer active: " + shortCode);
    }
}
