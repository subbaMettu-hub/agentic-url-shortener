package com.assessment.urlshortener.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guards against the two most common URL-shortener abuse vectors: non-http(s) schemes (script
 * injection or local-file access via disallowed URI schemes) and malformed input that would
 * otherwise 500 downstream instead of failing validation cleanly.
 *
 * <p>Also rejects hosts that are literal loopback/private/link-local IPs (including the
 * 169.254.169.254 cloud metadata address) so the service can't be used as an open SSRF proxy
 * against internal infrastructure. This only inspects IP literals and the "localhost" name -
 * it deliberately does not resolve DNS hostnames here, because doing so would make every
 * validation call (and every unit test) depend on network access, and a resolve-then-connect
 * gap would still be vulnerable to DNS rebinding. A production deployment should re-check the
 * resolved address immediately before opening the outbound connection at redirect time.
 */
public final class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F.:]+$");

    private UrlValidator() {
    }

    public static boolean isValid(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())
                    || host == null || host.isBlank()) {
                return false;
            }
            return !isDisallowedHost(host);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isDisallowedHost(String host) {
        String normalized = host.toLowerCase();
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")) {
            return true;
        }
        // URI.getHost() returns bracketed IPv6 literals (e.g. "[::1]") with the brackets intact.
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (!IP_LITERAL.matcher(normalized).matches()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(normalized);
            return address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress()
                    || address.isMulticastAddress();
        } catch (UnknownHostException e) {
            return true;
        }
    }
}
