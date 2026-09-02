package com.assessment.urlshortener.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * Guards against the two most common URL-shortener abuse vectors: non-http(s) schemes (script
 * injection or local-file access via disallowed URI schemes) and malformed input that would
 * otherwise 500 downstream instead of failing validation cleanly.
 */
public final class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private UrlValidator() {
    }

    public static boolean isValid(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            return scheme != null
                    && ALLOWED_SCHEMES.contains(scheme.toLowerCase())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
