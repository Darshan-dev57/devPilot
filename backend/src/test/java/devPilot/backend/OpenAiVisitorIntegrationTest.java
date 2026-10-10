package devPilot.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import devPilot.backend.services.ai.AiKeyResolver;
import devPilot.backend.support.FakeGitHub;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A server with no AI key at all, and a visitor who only has an OpenAI key. The real
 * OpenAI and Gemini clients run against a local fake of each API (wire formats from the
 * providers' docs), so indexing really goes through OpenAI embeddings into
 * vector_store_openai, and questions are searched there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpenAiVisitorIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static final String OPENAI_KEY = "sk-visitor-openai-test";
    static final String GEMINI_KEY = "AIza-visitor-gemini-test";

    static final FakeGitHub GITHUB = startGitHub();
    static final FakeAi AI = new FakeAi();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.github.api-base-url", GITHUB::baseUrl);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("app.ai.gemini.api-key", () -> "");
        registry.add("app.ai.openai.api-key", () -> "");
        registry.add("app.ai.provider", () -> "");
        registry.add("app.ai.openai.base-url", () -> AI.base() + "/openai/v1");
        registry.add("app.ai.gemini.base-url", () -> AI.base() + "/gemini");
        registry.add("app.ai.retry-base-delay-ms", () -> "10");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    static String repoId;

    static final String VISITOR = "33333333-3333-3333-3333-333333333333";
    static final String OTHER = "44444444-4444-4444-4444-444444444444";

    static final Map<String, String> OPENAI = Map.of(
            "X-Chat-Provider", "openai", "X-Chat-Key", OPENAI_KEY, "X-Chat-Model", "gpt-test");
    static final Map<String, String> CLAUDE = Map.of(
            "X-Chat-Provider", "claude", "X-Chat-Key", "sk-ant-test", "X-Chat-Model", "claude-test");
    static final Map<String, String> NO_KEYS = Map.of();

    private static FakeGitHub startGitHub() {
        try {
            FakeGitHub gitHub = new FakeGitHub();
            gitHub.commit(new LinkedHashMap<>(Map.of(
                    "src/main/java/demo/RateLimiter.java", """
                            package demo;

                            /** Throttles outgoing GitHub calls with a token bucket. */
                            public class RateLimiter {
                                public boolean tryAcquire() {
                                    return bucket.take();
                                }
                            }
                            """,
                    "src/main/java/demo/Greeter.java", """
                            package demo;

                            public class Greeter {
                                public String greet(String name) {
                                    return "Hello " + name;
                                }
                            }
                            """)));
            return gitHub;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @AfterAll
    static void stop() {
        GITHUB.close();
        AI.close();
    }

    @Test
    @Order(1)
    void usageSaysTheOpenAiKeyCanIndex() throws Exception {
        JsonNode usage = json.readTree(get("/api/usage", VISITOR, OPENAI).body());
        assertThat(usage.get("configured").asBoolean()).isFalse();
        assertThat(usage.get("canIndex").asBoolean()).isTrue();
        assertThat(usage.get("ownIndexKey").asBoolean()).isTrue();
        assertThat(usage.get("indexProvider").asString()).isEqualTo("OpenAI");

        JsonNode claude = json.readTree(get("/api/usage", VISITOR, CLAUDE).body());
        assertThat(claude.get("canIndex").asBoolean()).isFalse();
    }

    @Test
    @Order(2)
    void withoutAnyKeyTheVisitorIsToldWhatToAdd() throws Exception {
        HttpResponse<String> added = post("/api/repos/by-url", "{\"owner\":\"acme\",\"name\":\"demo\"}", VISITOR, NO_KEYS);
        assertThat(added.statusCode()).isEqualTo(200);
        repoId = json.readTree(added.body()).get("id").asString();

        HttpResponse<String> none = post("/api/repos/" + repoId + "/index", "", VISITOR, NO_KEYS);
        assertThat(none.statusCode()).isEqualTo(503);
        assertThat(message(none)).isEqualTo(AiKeyResolver.NOT_CONFIGURED).doesNotContain("owner");

        HttpResponse<String> claude = post("/api/repos/" + repoId + "/index", "", VISITOR, CLAUDE);
        assertThat(claude.statusCode()).isEqualTo(503);
        assertThat(message(claude)).isEqualTo(AiKeyResolver.NEEDS_INDEX_KEY);
        assertThat(AI.requests).isEmpty();
    }

    @Test
    @Order(3)
    void anOpenAiKeyIndexesIntoTheOpenAiStore() throws Exception {
        assertThat(post("/api/repos/" + repoId + "/index", "", VISITOR, OPENAI).statusCode()).isEqualTo(202);
        JsonNode status = waitWhileIndexing();
        assertThat(status.get("indexStatus").asString()).isEqualTo("READY");
        assertThat(status.get("chunkCount").asInt()).isEqualTo(vectors("vector_store_openai")).isPositive();
        assertThat(jdbc.queryForObject("select embedding_provider from repositories where id = ?::uuid", String.class, repoId))
                .isEqualTo("OPENAI");
        assertThat(vectors("vector_store_gemini")).isZero();
        assertThat(AI.requests).allMatch(r -> r.startsWith("/openai/v1/embeddings Bearer " + OPENAI_KEY));
        assertThat(AI.requests.get(0)).contains("text-embedding-3-small");
    }

    @Test
    @Order(4)
    void questionsAreSearchedWithOpenAiAndAnsweredByTheVisitorsModel() throws Exception {
        AI.requests.clear();
        String sessionId = session(VISITOR);
        HttpResponse<String> stream = post("/api/chat/sessions/" + sessionId + "/messages",
                "{\"content\":\"where is the rate limiter token bucket?\"}", VISITOR, OPENAI);
        assertThat(stream.statusCode()).isEqualTo(200);
        String assistant = stream.body().substring(stream.body().indexOf("event:assistant_message"));
        JsonNode message = json.readTree(assistant.substring(assistant.indexOf("data:") + 5, assistant.indexOf("\n\n")));
        assertThat(message.get("content").asString()).isEqualTo("The token bucket is in RateLimiter.java.");
        assertThat(message.get("citations").get(0).get("filePath").asString()).isEqualTo("src/main/java/demo/RateLimiter.java");
        assertThat(AI.requests.get(0)).startsWith("/openai/v1/embeddings Bearer " + OPENAI_KEY);
        assertThat(AI.requests.get(1)).startsWith("/openai/v1/chat/completions Bearer " + OPENAI_KEY).contains("\"model\":\"gpt-test\"");

        Integer stored = jdbc.queryForObject("""
                select count(*) from (
                    select content as t from chat_messages union all
                    select coalesce(error_message, '') from repositories) x
                where t like '%' || ? || '%'""", Integer.class, OPENAI_KEY);
        assertThat(stored).as("the visitor's key is never written to the database").isZero();
    }

    @Test
    @Order(5)
    void aVisitorWithoutAnOpenAiKeyIsToldHowToChatWithThisRepo() throws Exception {
        post("/api/repos/by-url", "{\"owner\":\"acme\",\"name\":\"demo\"}", OTHER, NO_KEYS);
        HttpResponse<String> response = post("/api/chat/sessions/" + session(OTHER) + "/messages",
                "{\"content\":\"where is the rate limiter?\"}", OTHER, Map.of("X-Gemini-Key", GEMINI_KEY));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(message(response)).startsWith("This repo was indexed with an OpenAI key.");
    }

    @Test
    @Order(6)
    void indexingAgainWithGeminiMovesTheRepoAndNeverMixesStores() throws Exception {
        AI.requests.clear();
        assertThat(post("/api/repos/" + repoId + "/index", "", OTHER, Map.of("X-Gemini-Key", GEMINI_KEY)).statusCode())
                .isEqualTo(202);
        JsonNode status = waitWhileIndexing();
        assertThat(status.get("indexStatus").asString()).isEqualTo("READY");
        assertThat(status.get("lastIndexMode").asString()).isEqualTo("FULL");
        assertThat(jdbc.queryForObject("select embedding_provider from repositories where id = ?::uuid", String.class, repoId))
                .isEqualTo("GEMINI");
        assertThat(vectors("vector_store_gemini")).isEqualTo(status.get("chunkCount").asInt()).isPositive();
        assertThat(vectors("vector_store_openai")).as("old OpenAI vectors are gone").isZero();
        assertThat(AI.requests).allMatch(r -> r.startsWith("/gemini/v1beta/models/"));

        // Now the OpenAI-only visitor gets a clear way forward instead of a wrong-store search.
        HttpResponse<String> response = post("/api/chat/sessions/" + session(VISITOR) + "/messages",
                "{\"content\":\"where is the rate limiter?\"}", VISITOR, OPENAI);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(message(response)).startsWith("This repo was indexed with Gemini.");
    }

    @Test
    @Order(7)
    void aRejectedOpenAiKeyIsRefusedBeforeAnythingIsCleared() throws Exception {
        int before = vectors("vector_store_gemini");
        AI.rejectOpenAi = true;
        try {
            HttpResponse<String> response = post("/api/repos/" + repoId + "/index", "", VISITOR, OPENAI);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(message(response)).isEqualTo("Your OpenAI key was rejected. Check it in \"Use your own key\".");
        } finally {
            AI.rejectOpenAi = false;
        }
        assertThat(vectors("vector_store_gemini")).isEqualTo(before);
    }

    private String session(String visitor) throws Exception {
        HttpResponse<String> session = post("/api/chat/sessions", "{\"repositoryId\":\"" + repoId + "\"}", visitor, NO_KEYS);
        return json.readTree(session.body()).get("id").asString();
    }

    private String message(HttpResponse<String> response) {
        return json.readTree(response.body()).get("message").asString();
    }

    private int vectors(String table) {
        Boolean exists = jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, "public." + table);
        if (!Boolean.TRUE.equals(exists)) {
            return 0;
        }
        return jdbc.queryForObject("select count(*) from " + table + " where metadata->>'repoId' = ?", Integer.class, repoId);
    }

    private JsonNode waitWhileIndexing() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            JsonNode status = json.readTree(get("/api/repos/" + repoId + "/status", VISITOR, NO_KEYS).body());
            if (!"INDEXING".equals(status.get("indexStatus").asString()) || System.currentTimeMillis() > deadline) {
                return status;
            }
            Thread.sleep(100);
        }
    }

    private HttpResponse<String> get(String path, String visitor, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Visitor-Id", visitor);
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String visitor, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-Visitor-Id", visitor)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Local stand-ins for the OpenAI and Gemini APIs. Embeddings are hashed bags of words. */
    static final class FakeAi implements AutoCloseable {
        final HttpServer server;
        final List<String> requests = new CopyOnWriteArrayList<>();
        volatile boolean rejectOpenAi;
        final JsonMapper json = JsonMapper.builder().build();

        FakeAi() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
            server.createContext("/", this::handle);
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            requests.add(path + " " + auth + " " + body);
            if (path.startsWith("/openai") && rejectOpenAi) {
                send(exchange, 401, "application/json",
                        "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}");
            } else if (path.equals("/openai/v1/embeddings")) {
                openAiEmbeddings(exchange, json.readTree(body));
            } else if (path.equals("/openai/v1/chat/completions")) {
                openAiChat(exchange);
            } else if (path.startsWith("/gemini/v1beta/models/") && path.endsWith(":batchEmbedContents")) {
                geminiEmbeddings(exchange, json.readTree(body));
            } else {
                send(exchange, 404, "application/json", "{\"error\":{\"message\":\"no such path " + path + "\"}}");
            }
        }

        void openAiEmbeddings(HttpExchange exchange, JsonNode request) throws IOException {
            JsonNode input = request.get("input");
            int dims = request.has("dimensions") ? request.get("dimensions").asInt() : 1536;
            boolean base64 = request.has("encoding_format") && "base64".equals(request.get("encoding_format").asString());
            StringBuilder data = new StringBuilder();
            for (int i = 0; i < input.size(); i++) {
                float[] v = vector(input.get(i).asString(), dims);
                data.append(i == 0 ? "" : ",").append("{\"object\":\"embedding\",\"index\":").append(i).append(",\"embedding\":");
                if (base64) {
                    ByteBuffer buffer = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
                    for (float x : v) {
                        buffer.putFloat(x);
                    }
                    data.append('"').append(Base64.getEncoder().encodeToString(buffer.array())).append('"');
                } else {
                    data.append(array(v));
                }
                data.append('}');
            }
            send(exchange, 200, "application/json", "{\"object\":\"list\",\"data\":[" + data
                    + "],\"model\":\"text-embedding-3-small\",\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}");
        }

        void geminiEmbeddings(HttpExchange exchange, JsonNode request) throws IOException {
            StringBuilder out = new StringBuilder();
            JsonNode items = request.get("requests");
            for (int i = 0; i < items.size(); i++) {
                JsonNode item = items.get(i);
                String text = item.get("content").get("parts").get(0).get("text").asString();
                out.append(i == 0 ? "" : ",").append("{\"values\":")
                        .append(array(vector(text, item.get("outputDimensionality").asInt()))).append('}');
            }
            send(exchange, 200, "application/json", "{\"embeddings\":[" + out + "]}");
        }

        void openAiChat(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (String piece : List.of("The token bucket", " is in", " RateLimiter.java.")) {
                    out.write(("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                            + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + piece
                            + "\"},\"finish_reason\":null}]}\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        }

        static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        static String array(float[] v) {
            StringBuilder s = new StringBuilder("[");
            for (int j = 0; j < v.length; j++) {
                s.append(j == 0 ? "" : ",").append(v[j]);
            }
            return s.append(']').toString();
        }

        static float[] vector(String text, int dims) {
            float[] v = new float[dims];
            for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (word.length() > 2) {
                    v[Math.floorMod(word.hashCode(), dims)] += 1f;
                }
            }
            double norm = 0;
            for (float x : v) {
                norm += x * x;
            }
            norm = Math.sqrt(norm == 0 ? 1 : norm);
            if (norm == 1 && v[0] == 0) {
                v[0] = 1f;
            }
            for (int j = 0; j < v.length; j++) {
                v[j] /= (float) norm;
            }
            return v;
        }
    }
}
