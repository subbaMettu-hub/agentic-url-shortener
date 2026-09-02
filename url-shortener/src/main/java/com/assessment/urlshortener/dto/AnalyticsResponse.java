package com.assessment.urlshortener.dto;

import java.time.Instant;

public record AnalyticsResponse(
        String shortCode,
        long totalClicks,
        Instant createdAt,
        Instant lastAccessedAt,
        double averageClicksPerDay,
        boolean active,
        boolean expired
) {
}
