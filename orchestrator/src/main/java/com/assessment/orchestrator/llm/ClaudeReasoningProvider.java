package com.assessment.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Real Claude API-backed reasoning, active only when the ANTHROPIC_API_KEY environment variable
 * is set. Uses only the JDK's built-in HttpClient and the Jackson dependency this module already
 * carries for JSON audit/metrics output - no new dependency, consistent with the orchestrator's
 * "nothing but the JDK + Jackson" design (see docs/ARCHITECTURE.md).
 *
 * Every failure mode (no key, network error, timeout, non-2xx, malformed body) is caught here and
 * surfaced as {@code Optional.empty()} - this provider is an enhancement layered on top of the
 * deterministic pipeline, never a hard dependency for a run to complete.
 */
public final class ClaudeReasoningProvider implements ReasoningProvider {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String DEFAULT_MODEL = "claude-haiku-4-5-20251001";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClaudeReasoningProvider() {
        this(System.getenv("ANTHROPIC_API_KEY"), System.getenv().getOrDefault("ANTHROPIC_MODEL", DEFAULT_MODEL));
    }

    ClaudeReasoningProvider(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public Optional<String> complete(String systemPrompt, String userPrompt) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 1536,
                    "system", systemPrompt,
                    "messages", List.of(Map.of("role", "user", "content", userPrompt)));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(REQUEST_TIMEOUT)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }

            JsonNode content = mapper.readTree(response.body()).path("content");
            if (!content.isArray() || content.isEmpty()) {
                return Optional.empty();
            }
            String text = content.get(0).path("text").asText(null);
            return (text == null || text.isBlank()) ? Optional.empty() : Optional.of(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
