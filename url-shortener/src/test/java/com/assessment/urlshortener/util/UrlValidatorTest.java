package com.assessment.urlshortener.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?query=1",
            "https://sub.example.com:8443/a/b/c#frag"
    })
    void acceptsWellFormedHttpUrls(String url) {
        assertTrue(UrlValidator.isValid(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "file:///etc/passwd",
            "ftp://example.com/file",
            "not a url",
            "",
            "://missing-scheme"
    })
    void rejectsUnsafeOrMalformedUrls(String url) {
        assertFalse(UrlValidator.isValid(url));
    }
}
