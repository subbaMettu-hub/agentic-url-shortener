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

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost/admin",
            "http://LOCALHOST:8080/admin",
            "http://sub.localhost/admin",
            "http://127.0.0.1/admin",
            "http://127.0.0.5:9000/",
            "http://0.0.0.0/",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.5/internal",
            "http://172.16.0.1/internal",
            "http://192.168.1.1/internal",
            "http://[::1]/admin",
            "http://224.0.0.1/"
    })
    void rejectsSsrfTargetingLoopbackPrivateOrLinkLocalHosts(String url) {
        assertFalse(UrlValidator.isValid(url));
    }
}
