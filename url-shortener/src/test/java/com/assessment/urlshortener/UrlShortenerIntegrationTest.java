package com.assessment.urlshortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class UrlShortenerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullLifecycle_createRedirectAnalyticsDelete() throws Exception {
        String createBody = """
                {"longUrl": "https://example.com/very/long/path"}
                """;

        String createResponseJson = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode created = objectMapper.readTree(createResponseJson);
        String shortCode = created.get("shortCode").asText();

        mockMvc.perform(get("/api/v1/urls/{code}", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(0));

        mockMvc.perform(get("/{code}", shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/very/long/path"));

        mockMvc.perform(get("/api/v1/urls/{code}/analytics", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(1));

        mockMvc.perform(delete("/api/v1/urls/{code}", shortCode))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/urls/{code}", shortCode))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/{code}", shortCode))
                .andExpect(status().isNotFound());
    }

    @Test
    void createShortUrl_rejectsMalformedRequest() throws Exception {
        String badBody = """
                {"longUrl": "not-a-url"}
                """;

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void createShortUrl_rejectsMissingLongUrl() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void createShortUrl_duplicateCustomAliasReturnsConflict() throws Exception {
        String body = """
                {"longUrl": "https://example.com/a", "customAlias": "dup-alias-test"}
                """;

        mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void redirect_unknownCodeReturnsNotFound() throws Exception {
        mockMvc.perform(get("/{code}", "doesnotexist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void qrCode_returnsPngForExistingShortUrl() throws Exception {
        String createBody = """
                {"longUrl": "https://example.com/qr-target"}
                """;
        String createResponseJson = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String shortCode = objectMapper.readTree(createResponseJson).get("shortCode").asText();

        byte[] png = mockMvc.perform(get("/api/v1/urls/{code}/qrcode", shortCode))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(png.length).isGreaterThan(100);
    }

    @Test
    void health_endpointReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
