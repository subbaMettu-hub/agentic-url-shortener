package com.assessment.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreateUrlRequest(

        @NotBlank(message = "longUrl is required")
        String longUrl,

        @Pattern(regexp = "^[a-zA-Z0-9_-]{3,32}$", message = "customAlias must be 3-32 chars of letters, digits, '-' or '_'")
        String customAlias,

        @Positive(message = "expiresInSeconds must be positive")
        Long expiresInSeconds
) {
}
