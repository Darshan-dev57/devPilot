package devPilot.backend.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import devPilot.backend.services.ai.AiModelFactory;
import devPilot.backend.services.ai.AiProvider;
import reactor.core.publisher.Flux;

/**
 * Replaces the OpenAI/Gemini models with deterministic local fakes, so the whole pipeline
 * (GitHub download, chunking, pgvector, retrieval, SSE) runs in CI with no API key.
 * Embeddings are hashed bags of words: chunks sharing words with the question rank higher.
 */
public class FakeAiModelFactory extends AiModelFactory {

    public static final List<String> TOKENS = List.of("Rate", " limiting", " lives in", "\nRateLimiter.java");

    public final AtomicInteger embeddedTexts = new AtomicInteger();

    /** When set, new embedding calls fail the way a revoked server key does. */
    public final AtomicBoolean rejectKey = new AtomicBoolean();

    /** provider:key of every chat model built, to check which key answered. */
    public final List<String> chatKeys = new java.util.concurrent.CopyOnWriteArrayList<>();

    public FakeAiModelFactory(JdbcTemplate jdbcTemplate, RestClient.Builder builder) {
        super(jdbcTemplate, builder);
    }

    @Override
    protected int dimensions(AiProvider provider) {
        return 1536;
    }

    @Override
    protected EmbeddingModel embeddingModel(AiProvider provider, String apiKey) {
        return new HashingEmbeddingModel(rejectKey.get());
    }

    @Override
    protected ChatModel buildChatModel(AiProvider provider, String apiKey) {
        chatKeys.add(provider + ":" + apiKey);
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(String.join("", TOKENS)))));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.fromIterable(TOKENS)
                        .map(t -> new ChatResponse(List.of(new Generation(new AssistantMessage(t)))));
            }
        };
    }

    private class HashingEmbeddingModel extends AbstractEmbeddingModel {
        private final boolean reject;

        HashingEmbeddingModel(boolean reject) {
            this.reject = reject;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (reject) {
                throw new RuntimeException("401 invalid_api_key");
            }
            List<Embedding> out = new ArrayList<>();
            int i = 0;
            for (String text : request.getInstructions()) {
                if (!"ping".equals(text)) { // the key check before each job
                    embeddedTexts.incrementAndGet();
                }
                out.add(new Embedding(vector(text), i++));
            }
            return new EmbeddingResponse(out);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        @Override
        public int dimensions() {
            return 1536;
        }

        private float[] vector(String text) {
            float[] v = new float[1536];
            for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (word.length() > 2) {
                    v[Math.floorMod(word.hashCode(), 1536)] += 1f;
                }
            }
            double norm = 0;
            for (float x : v) {
                norm += x * x;
            }
            norm = Math.sqrt(norm == 0 ? 1 : norm);
            for (int j = 0; j < v.length; j++) {
                v[j] /= (float) norm;
            }
            return v;
        }
    }
}
