package com.assessment.urlshortener.exception;

public class UrlNotFoundException extends RuntimeException {
    public UrlNotFoundException(String shortCode) {
        super("No active short URL found for code: " + shortCode);
    }
}
