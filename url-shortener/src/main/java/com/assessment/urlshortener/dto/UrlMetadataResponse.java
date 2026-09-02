package com.assessment.urlshortener.dto;

import java.time.Instant;

public record UrlMetadataResponse(
        String shortCode,
        String shortUrl,
        String longUrl,
        Instant createdAt,
        Instant expiresAt,
        Instant lastAccessedAt,
        long clickCount,
        boolean active,
        boolean customAlias
) {
}
