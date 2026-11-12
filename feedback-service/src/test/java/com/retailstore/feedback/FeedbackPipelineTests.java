package com.retailstore.feedback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailstore.feedback.model.FeedbackSummary;
import com.retailstore.feedback.service.FeedbackService;
import com.retailstore.feedback.service.GeminiService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FeedbackPipelineTests {

    private static final String BASE_URL = "https://gemini.example";
    private static final String API_URL = BASE_URL + "/v1beta/models/test-model:generateContent";
    private static final String API_KEY = "local-test-key";
    private static final String RESPONSE = """
            {"candidates":[{"content":{"parts":[
                {"text":"internal","thought":true},
                {"text":"{\\"category\\":\\"Delivery\\",\\"actionableInsight\\":\\"Check the courier handoff.\\"}"}
            ]}}]}
            """;

    @TempDir
    Path tempDir;

    private Path report;
    private MockRestServiceServer provider;
    private final RestTemplateBuilder builder = new RestTemplateBuilder()
            .additionalCustomizers(client -> provider = MockRestServiceServer.bindTo(client).build());

    @BeforeEach
    void writeReport() throws IOException {
        report = tempDir.resolve("sentiment_feedback_output.txt");
        Files.writeString(report, """
                ## Detailed Feedback Entries

                Feedback #1
                Customer: Demo Customer
                Department: Delivery
                Date: 2026-11-01
                Comment: The parcel arrived damaged.
                Sentiment: Negative
                """);
    }

    @Test
    void enrichesAndCachesFeedback() throws IOException {
        FeedbackService service = createService(API_KEY);
        provider.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", API_KEY))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        FeedbackSummary summary = service.generateFeedbackSummary();
        assertThat(summary.getTotalFeedback()).isEqualTo(1);
        assertThat(summary.getUnenrichedFeedback()).isZero();
        assertThat(summary.getSentimentCounts()).containsEntry("Negative", 1);
        assertThat(summary.getCategoryCounts()).containsEntry("Delivery", 1);
        assertThat(service.getEnhancedFeedback().getFirst().getActionableInsight())
                .isEqualTo("Check the courier handoff.");
        provider.verify();
    }

    @Test
    void keepsSentimentAndRetriesAfterProviderFailure() throws IOException {
        FeedbackService service = createService(API_KEY);
        provider.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("untrusted provider response: " + API_KEY));
        provider.expect(requestTo(API_URL))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        FeedbackSummary partial = service.generateFeedbackSummary();
        assertThat(partial.getUnenrichedFeedback()).isEqualTo(1);
        assertThat(partial.getSentimentCounts()).containsEntry("Negative", 1);
        assertThat(partial.getCategoryCounts()).isEmpty();
        assertThat(partial.getAllFeedback().getFirst().getActionableInsight())
                .isEqualTo("Gemini enrichment unavailable. Reload to retry.");
        assertThat(service.generateFeedbackSummary().getUnenrichedFeedback()).isZero();
        provider.verify();
    }

    @Test
    void showsSentimentWithoutAnApiKey() throws IOException {
        FeedbackService service = createService("");

        FeedbackSummary summary = service.generateFeedbackSummary();
        assertThat(summary.getSentimentCounts()).containsEntry("Negative", 1);
        assertThat(summary.getCategoryCounts()).isEmpty();
        assertThat(summary.getUnenrichedFeedback()).isEqualTo(1);
        provider.verify();
    }

    private FeedbackService createService(String key) {
        GeminiService gemini = new GeminiService(builder, new ObjectMapper(), key, "test-model", BASE_URL);
        return new FeedbackService(gemini, report.toString());
    }
}
