package devPilot.backend.services.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class RetryingEmbeddingModelTest {

    static RuntimeException geminiPerMinute429() {
        String body = "{\"error\":{\"code\":429,\"message\":\"Quota exceeded for metric: "
                + "generativelanguage.googleapis.com/embed_content_free_tier_requests, limit: 100\\nPlease retry in 32.07s.\","
                + "\"status\":\"RESOURCE_EXHAUSTED\"}}";
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void usesTheWaitGoogleAsksFor() {
        assertThat(RetryingEmbeddingModel.waitMs(geminiPerMinute429(), 2000)).isEqualTo(33_070);
        assertThat(RetryingEmbeddingModel.waitMs(new RuntimeException("\"retryDelay\": \"7s\""), 2000)).isEqualTo(8_000);
        assertThat(RetryingEmbeddingModel.waitMs(new RuntimeException("429"), 2000)).isEqualTo(2000);
    }

    @Test
    void indexJobsWaitAndCarryOn() {
        AtomicInteger calls = new AtomicInteger();
        List<String> phases = new ArrayList<>();
        var model = new RetryingEmbeddingModel(failing(calls, 3), 1, 8);
        RetryingEmbeddingModel.WAIT_LISTENER.set(phases::add);
        try {
            // waitMs would honour "retry in Ns"; this fake says nothing, so the 1 ms base applies.
            assertThat(model.call(new EmbeddingRequest(List.of("a"), null)).getResults()).hasSize(1);
        } finally {
            RetryingEmbeddingModel.WAIT_LISTENER.remove();
        }
        assertThat(calls.get()).isEqualTo(4);
        assertThat(phases).hasSize(3).allMatch(p -> p.contains("free per-minute limit"));
    }

    @Test
    void chatQuestionsOnlyRetryOnce() {
        AtomicInteger calls = new AtomicInteger();
        var model = new RetryingEmbeddingModel(failing(calls, 5), 1, 8);
        assertThatThrownBy(() -> model.call(new EmbeddingRequest(List.of("a"), null))).hasMessageContaining("429");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void otherErrorsAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingModel broken = new Fake(calls, Integer.MAX_VALUE, new IllegalStateException("401 invalid key"));
        assertThatThrownBy(() -> new RetryingEmbeddingModel(broken, 1, 8).call(new EmbeddingRequest(List.of("a"), null)))
                .hasMessageContaining("401");
        assertThat(calls.get()).isEqualTo(1);
    }

    static EmbeddingModel failing(AtomicInteger calls, int failures) {
        return new Fake(calls, failures, new RuntimeException("429 Too Many Requests"));
    }

    record Fake(AtomicInteger calls, int failures, RuntimeException error) implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (calls.incrementAndGet() <= failures) {
                throw error;
            }
            return new EmbeddingResponse(List.of(new Embedding(new float[] { 1f }, 0)));
        }

        @Override
        public float[] embed(Document document) {
            return new float[] { 1f };
        }
    }
}
