package com.assessment.urlshortener.exception;

public class InvalidUrlException extends RuntimeException {
    public InvalidUrlException(String reason) {
        super(reason);
    }
}
