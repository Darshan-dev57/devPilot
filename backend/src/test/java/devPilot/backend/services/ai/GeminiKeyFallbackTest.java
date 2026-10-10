package devPilot.backend.services.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Several server Gemini keys against a fake Gemini API that answers per key. */
class GeminiKeyFallbackTest {

    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private static final String DAILY_429 = "{\"error\":{\"code\":429,\"message\":\"You exceeded your current quota. "
            + "Quota exceeded for metric: generativelanguage.googleapis.com/embed_content_free_tier_requests, limit: 1000\","
            + "\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\","
            + "\"violations\":[{\"quotaId\":\"EmbedContentRequestsPerDayPerProjectPerModel-FreeTier\"}]}]}}";
    private static final String MINUTE_429 = "{\"error\":{\"code\":429,\"message\":\"Quota exceeded for metric: "
            + "generativelanguage.googleapis.com/embed_content_free_tier_requests, limit: 100\\nPlease retry in 20s.\","
            + "\"status\":\"RESOURCE_EXHAUSTED\"}}";
    private static final String INVALID_400 = "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\","
            + "\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}";

    private HttpServer server;
    private String baseUrl;
    private final Map<String, String> mode = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-10T18:00:00Z"));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestHeaders().getFirst("x-goog-api-key");
        hits.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        String m = mode.getOrDefault(key, "ok");
        boolean chat = exchange.getRequestURI().getPath().contains("streamGenerateContent");
        switch (m) {
            case "daily" -> reply(exchange, 429, DAILY_429, "application/json");
            case "minute" -> reply(exchange, 429, MINUTE_429, "application/json");
            case "invalid" -> reply(exchange, 400, INVALID_400, "application/json");
            default -> {
                if (chat) {
                    String text = "answer from " + key;
                    reply(exchange, 200, "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text + "\"}]}}]}\n\n",
                            "text/event-stream");
                } else {
                    reply(exchange, 200, "{\"embeddings\":[{\"values\":[0.1,0.2,0.3]}]}", "application/json");
                }
            }
        }
    }

    private static void reply(HttpExchange exchange, int status, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private GeminiKeyPool pool(String... keys) {
        return new GeminiKeyPool(List.of(keys), clock, PACIFIC);
    }

    private EmbeddingModel embeddings(GeminiKeyPool pool) {
        return new RetryingEmbeddingModel(new KeyFallbackEmbeddingModel(pool,
                key -> new GeminiEmbeddingModel(key, baseUrl, "x-goog-api-key", "gemini-embedding-2", 3, RestClient.create())),
                1, 8);
    }

    private ChatModel chat(GeminiKeyPool pool) {
        return new KeyFallbackChatModel(pool, key -> new GeminiChatModel(baseUrl, "gemini-3.5-flash-lite", key, "x-goog-api-key"));
    }

    private static EmbeddingRequest request() {
        return new EmbeddingRequest(List.of("hello"), null);
    }

    private static String answer(ChatModel model) {
        return String.join("", model.stream(new Prompt(new UserMessage("hi")))
                .map(r -> r.getResult().getOutput().getText())
                .collectList()
                .block());
    }

    private int hits(String key) {
        return hits.getOrDefault(key, new AtomicInteger()).get();
    }

    @Test
    void parsesCommaSeparatedKeys() {
        assertThat(GeminiKeyPool.parse("k1, k2 ,k3,,k2\n")).containsExactly("k1", "k2", "k3");
        assertThat(GeminiKeyPool.parse("only-one")).containsExactly("only-one");
        assertThat(GeminiKeyPool.parse("  ")).isEmpty();
        assertThat(GeminiKeyPool.parse(null)).isEmpty();
    }

    @Test
    void aKeyOutOfDailyQuotaHandsTheSameRequestToTheNextKey() {
        mode.put("k1", "daily");
        GeminiKeyPool pool = pool("k1", "k2");
        EmbeddingModel model = embeddings(pool);

        assertThat(model.call(request()).getResults()).hasSize(1);
        assertThat(hits("k1")).isEqualTo(1);
        assertThat(hits("k2")).isEqualTo(1);

        // k1 stays benched until midnight Pacific instead of being asked again.
        model.call(request());
        assertThat(hits("k1")).isEqualTo(1);
        assertThat(hits("k2")).isEqualTo(2);
        assertThat(pool.available()).containsExactly(1);

        // After Google's daily reset it is back in the rotation.
        mode.remove("k1");
        clock.set(Instant.parse("2026-10-11T07:00:01Z"));
        assertThat(pool.available()).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void aPerMinuteLimitMovesOnWithoutBenchingTheKey() {
        mode.put("k1", "minute");
        GeminiKeyPool pool = pool("k1", "k2");

        assertThat(embeddings(pool).call(request()).getResults()).hasSize(1);
        assertThat(hits("k2")).isEqualTo(1);
        assertThat(pool.available()).containsExactly(1, 0);
    }

    @Test
    void anInvalidKeyIsSkipped() {
        mode.put("k1", "invalid");
        GeminiKeyPool pool = pool("k1", "k2", "k3");

        assertThat(embeddings(pool).call(request()).getResults()).hasSize(1);
        assertThat(answer(chat(pool))).isEqualTo("answer from k2");
        assertThat(hits("k1")).isEqualTo(1);
        assertThat(hits("k3")).isZero();
    }

    @Test
    void chatFallsBackToTheNextKey() {
        mode.put("k1", "daily");
        assertThat(answer(chat(pool("k1", "k2")))).isEqualTo("answer from k2");
    }

    @Test
    void whenEveryKeyIsUsedUpTheVisitorSeesTheDailyQuotaMessage() {
        mode.put("k1", "daily");
        mode.put("k2", "invalid");
        mode.put("k3", "daily");
        GeminiKeyPool pool = pool("k1", "k2", "k3");
        String expected = "The free Gemini quota for today is used up. Please try again tomorrow.";

        assertThatThrownBy(() -> embeddings(pool).call(request()))
                .isInstanceOf(RetryingEmbeddingModel.DailyQuotaException.class)
                .satisfies(e -> assertThat(AiErrors.describe(e, AiProvider.GEMINI)).isEqualTo(expected));
        assertThat(hits("k1") + hits("k2") + hits("k3")).isEqualTo(3);

        // Later requests fail at once without calling Google again.
        assertThatThrownBy(() -> answer(chat(pool)))
                .satisfies(e -> assertThat(AiErrors.describe(e, AiProvider.GEMINI)).isEqualTo(expected));
        assertThat(hits("k1") + hits("k2") + hits("k3")).isEqualTo(3);
    }

    @Test
    void everyKeyRejectedSaysTheKeyWasRejected() {
        mode.put("k1", "invalid");
        mode.put("k2", "invalid");
        GeminiKeyPool pool = pool("k1", "k2");
        assertThatThrownBy(() -> embeddings(pool).call(request()))
                .satisfies(e -> assertThat(AiErrors.describe(e, AiProvider.GEMINI)).containsIgnoringCase("rejected"));
    }

    @Test
    void aSingleKeyServerKeepsItsUsualModels() {
        AiModelFactory factory = new AiModelFactory(null, RestClient.builder());
        ReflectionTestUtils.setField(factory, "geminiBaseUrl", baseUrl);
        ReflectionTestUtils.setField(factory, "geminiKeyHeader", "x-goog-api-key");
        ReflectionTestUtils.setField(factory, "geminiChatModel", "gemini-3.5-flash-lite");
        ReflectionTestUtils.setField(factory, "geminiEmbeddingModel", "gemini-embedding-2");
        ReflectionTestUtils.setField(factory, "geminiEmbeddingDimensions", 3);
        factory.setGeminiKeys(pool("only"));

        assertThat(factory.chatModel(AiProvider.GEMINI, "only")).isInstanceOf(GeminiChatModel.class);
        assertThat(factory.serverEmbeddingModel(AiProvider.GEMINI, "only").call(request()).getResults()).hasSize(1);
        assertThat(hits("only")).isEqualTo(1);
    }

    @Test
    void severalKeysTurnOnFallbackInTheFactory() {
        mode.put("k1", "daily");
        AiModelFactory factory = new AiModelFactory(null, RestClient.builder());
        ReflectionTestUtils.setField(factory, "geminiBaseUrl", baseUrl);
        ReflectionTestUtils.setField(factory, "geminiKeyHeader", "x-goog-api-key");
        ReflectionTestUtils.setField(factory, "geminiChatModel", "gemini-3.5-flash-lite");
        ReflectionTestUtils.setField(factory, "geminiEmbeddingModel", "gemini-embedding-2");
        ReflectionTestUtils.setField(factory, "geminiEmbeddingDimensions", 3);
        factory.setGeminiKeys(pool("k1", "k2"));

        assertThat(factory.chatModel(AiProvider.GEMINI, "k1")).isInstanceOf(KeyFallbackChatModel.class);
        factory.verifyKey(new AiKeyResolver.UserAiKey(AiProvider.GEMINI, "k1"));
        assertThat(hits("k2")).isEqualTo(1);
    }

    @Test
    void theResolverUsesTheFirstKeyAndCountsAsConfigured() {
        var resolver = new AiKeyResolver(" k1 , k2 ", "", "");
        assertThat(resolver.configured()).isTrue();
        assertThat(resolver.requireKey().apiKey()).isEqualTo("k1");
        assertThat(new AiKeyResolver("single", "", "").requireKey().apiKey()).isEqualTo("single");
        assertThat(new AiKeyResolver("", "", "").configured()).isFalse();
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock outer = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId z) {
                    return outer.withZone(z);
                }

                @Override
                public Instant instant() {
                    return outer.now;
                }
            };
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
