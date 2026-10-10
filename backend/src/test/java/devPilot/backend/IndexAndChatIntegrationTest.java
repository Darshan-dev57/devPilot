package devPilot.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import devPilot.backend.support.FakeAiModelFactory;
import devPilot.backend.support.FakeGitHub;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End to end against a real Postgres + pgvector (Testcontainers) and a fake GitHub over
 * real HTTP: add a repo, full index, chat over SSE, hit the daily limit, then push commits and check that only
 * the changed files are re-embedded. The AI models are local fakes (no key needed).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IndexAndChatIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static final String KEY = "test-openai-key";

    static final Map<String, String> FILES = new LinkedHashMap<>(Map.of(
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
                    """,
            "README.md", "# Demo\n\nA demo repository.\n",
            "logo.png", "not really a png"));

    static final FakeGitHub GITHUB = startGitHub();

    @TestConfiguration
    static class FakeModels {
        @Bean
        @Primary
        FakeAiModelFactory fakeAiModelFactory(JdbcTemplate jdbc, RestClient.Builder builder) {
            return new FakeAiModelFactory(jdbc, builder);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.github.api-base-url", GITHUB::baseUrl);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("app.ai.provider", () -> "openai");
        registry.add("app.ai.openai.api-key", () -> KEY);
        registry.add("app.ai.retry-base-delay-ms", () -> "10");
        registry.add("app.limits.questions-per-visitor", () -> "2");
        registry.add("app.limits.indexes-per-visitor", () -> "0");
    }

    @LocalServerPort
    int port;

    @Autowired
    FakeAiModelFactory models;

    @Autowired
    JdbcTemplate jdbc;

    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    static String repoId;

    static final String VISITOR_A = "11111111-1111-1111-1111-111111111111";
    static final String VISITOR_B = "22222222-2222-2222-2222-222222222222";

    private static FakeGitHub startGitHub() {
        try {
            FakeGitHub gitHub = new FakeGitHub();
            gitHub.commit(FILES);
            return gitHub;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @AfterAll
    static void stopGitHub() {
        GITHUB.close();
    }

    @Test
    @Order(1)
    void rejectsPrivateAndMissingRepos() throws Exception {
        assertThat(post("/api/repos/by-url", "{\"owner\":\"acme\",\"name\":\"secret\"}", null).statusCode()).isEqualTo(403);
        HttpResponse<String> missing = post("/api/repos/by-url", "{\"owner\":\"acme\",\"name\":\"nope\"}", null);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json.readTree(missing.body()).get("message").asString()).startsWith("Repository not found");
    }

    @Test
    @Order(2)
    void fullIndexStoresChunksWithLineNumbers() throws Exception {
        HttpResponse<String> added = post("/api/repos/by-url", "{\"owner\":\"acme\",\"name\":\"demo\"}", null);
        assertThat(added.statusCode()).isEqualTo(200);
        repoId = json.readTree(added.body()).get("id").asString();

        JsonNode status = indexAndWait();
        assertThat(status.get("indexStatus").asString()).isEqualTo("READY");
        assertThat(status.get("lastIndexMode").asString()).isEqualTo("FULL");
        assertThat(status.get("filesTotal").asInt()).isEqualTo(3); // the .png is skipped
        assertThat(status.get("chunkCount").asInt()).isEqualTo(countVectors());
        assertThat(status.get("indexedSha").asString()).isEqualTo(String.format("%040d", 1));

        Integer withLines = jdbc.queryForObject(
                "select count(*) from vector_store_openai where metadata->>'startLine' is not null", Integer.class);
        assertThat(withLines).isEqualTo(countVectors());
    }

    @Test
    @Order(3)
    void chatStreamsTokensAsJsonAndCitesTheRightFile() throws Exception {
        HttpResponse<String> session = post("/api/chat/sessions", "{\"repositoryId\":\"" + repoId + "\"}", null);
        String sessionId = json.readTree(session.body()).get("id").asString();

        HttpResponse<String> stream = post("/api/chat/sessions/" + sessionId + "/messages",
                "{\"content\":\"where is the rate limiter token bucket?\"}", KEY);
        String body = stream.body();

        assertThat(stream.statusCode()).isEqualTo(200);
        // Leading spaces and newlines survive because each token is a JSON string.
        assertThat(body).contains("data:\" limiting\"").contains("data:\"\\nRateLimiter.java\"");
        String assistant = body.substring(body.indexOf("event:assistant_message"));
        JsonNode message = json.readTree(assistant.substring(assistant.indexOf("data:") + 5, assistant.indexOf("\n\n")));
        assertThat(message.get("content").asString()).isEqualTo(String.join("", FakeAiModelFactory.TOKENS));
        JsonNode first = message.get("citations").get(0);
        assertThat(first.get("filePath").asString()).isEqualTo("src/main/java/demo/RateLimiter.java");
        assertThat(first.get("startLine").asInt()).isEqualTo(1);
    }

    @Test
    @Order(4)
    void theDailyQuestionLimitIsAFriendly429() throws Exception {
        HttpResponse<String> session = post("/api/chat/sessions", "{\"repositoryId\":\"" + repoId + "\"}", null);
        String sessionId = json.readTree(session.body()).get("id").asString();
        String path = "/api/chat/sessions/" + sessionId + "/messages";

        assertThat(post(path, "{\"content\":\"second question\"}", null).statusCode()).isEqualTo(200);
        HttpResponse<String> third = post(path, "{\"content\":\"third question\"}", null);
        assertThat(third.statusCode()).isEqualTo(429);
        assertThat(json.readTree(third.body()).get("message").asString())
                .isEqualTo("You've used today's 2 free questions. Come back tomorrow, or use your own key.");
        // The refused question is not stored in the chat history.
        assertThat(json.readTree(get("/api/chat/sessions/" + sessionId, VISITOR_A).body()).size()).isEqualTo(2);

        // With their own key the visitor goes past the limit, and the key is used only for that request.
        HttpResponse<String> own = postWithHeader(path, "{\"content\":\"fourth question\"}", "X-Gemini-Key", "visitor-own-key-123");
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(own.body()).contains("event:assistant_message");
        assertThat(models.chatKeys).contains("GEMINI:visitor-own-key-123");
        Integer stored = jdbc.queryForObject("""
                select count(*) from (
                    select content as t from chat_messages union all
                    select coalesce(citations::text, '') from chat_messages union all
                    select coalesce(error_message, '') from repositories) x
                where t like '%visitor-own-key-123%'""", Integer.class);
        assertThat(stored).as("the visitor's key is never written to the database").isZero();

        JsonNode usage = json.readTree(get("/api/usage", VISITOR_A).body());
        assertThat(usage.get("questionsLeft").asInt()).isZero();
        assertThat(usage.get("provider").asString()).isEqualTo("OpenAI");
        assertThat(json.readTree(get("/api/usage", VISITOR_B).body()).get("questionsLeft").asInt()).isEqualTo(2);
    }

    @Test
    @Order(5)
    void reindexOnlyEmbedsChangedFiles() throws Exception {
        Map<String, String> next = new LinkedHashMap<>(FILES);
        next.put("src/main/java/demo/Greeter.java", FILES.get("src/main/java/demo/Greeter.java").replace("Hello", "Hi"));
        next.remove("README.md");
        next.put("src/main/java/demo/Clock.java", "package demo;\n\npublic class Clock {}\n");
        GITHUB.commit(next);

        int embeddedBefore = models.embeddedTexts.get();
        JsonNode status = indexAndWait();

        assertThat(status.get("lastIndexMode").asString()).isEqualTo("INCREMENTAL");
        assertThat(status.get("filesTotal").asInt()).isEqualTo(2); // Greeter (modified) + Clock (added)
        assertThat(models.embeddedTexts.get() - embeddedBefore).isEqualTo(2);
        assertThat(vectorsFor("README.md")).isZero();
        assertThat(vectorsFor("src/main/java/demo/RateLimiter.java")).isEqualTo(1);
        assertThat(status.get("chunkCount").asInt()).isEqualTo(countVectors());

        JsonNode again = indexAndWait();
        assertThat(again.get("lastIndexMode").asString()).isEqualTo("UP_TO_DATE");
        assertThat(models.embeddedTexts.get() - embeddedBefore).isEqualTo(2);
    }

    @Test
    @Order(6)
    void aRejectedServerKeyIsRefusedUpFrontAndTheExistingIndexSurvives() throws Exception {
        int before = countVectors();
        GITHUB.commit(Map.of("src/A.java", "class A {}\n"));
        models.rejectKey.set(true);
        try {
            HttpResponse<String> response = post("/api/repos/" + repoId + "/index", "", null);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(json.readTree(response.body()).get("message").asString())
                    .isEqualTo("The server's OpenAI key was rejected. The site owner needs to check it.");
        } finally {
            models.rejectKey.set(false);
        }
        assertThat(countVectors()).isEqualTo(before);
        assertThat(waitWhileIndexing().get("indexStatus").asString()).isEqualTo("READY");
    }

    @Test
    @Order(7)
    void eachVisitorOnlySeesTheirOwnReposAndChats() throws Exception {
        assertThat(json.readTree(get("/api/repos", VISITOR_B).body()).size()).isZero();
        assertThat(get("/api/repos/" + repoId, VISITOR_B).statusCode()).isEqualTo(404);
        assertThat(get("/api/repos", "not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(http.send(HttpRequest.newBuilder(uri("/api/repos")).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(400);

        // B adds the same repo: it is already indexed, so B can chat without re-embedding.
        HttpResponse<String> added = post("/api/repos/by-url", "{\"owner\":\"acme\",\"name\":\"demo\"}", null, VISITOR_B);
        assertThat(json.readTree(added.body()).get("id").asString()).isEqualTo(repoId);
        assertThat(json.readTree(added.body()).get("indexStatus").asString()).isEqualTo("READY");
        assertThat(json.readTree(get("/api/chat/sessions?repositoryId=" + repoId, VISITOR_B).body()).size())
                .as("A's chats stay private").isZero();
    }

    @Test
    @Order(8)
    void removingDeletesDataOnlyWhenTheLastVisitorLeaves() throws Exception {
        assertThat(delete("/api/repos/" + repoId, VISITOR_B).statusCode()).isEqualTo(204);
        assertThat(json.readTree(get("/api/repos", VISITOR_B).body()).size()).isZero();
        assertThat(json.readTree(get("/api/repos", VISITOR_A).body()).size()).isEqualTo(1);
        assertThat(countVectors()).isPositive();

        assertThat(delete("/api/repos/" + repoId, VISITOR_A).statusCode()).isEqualTo(204);
        assertThat(json.readTree(get("/api/repos", VISITOR_A).body()).size()).isZero();
        assertThat(countVectors()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from repositories", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from chat_sessions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from chat_messages", Integer.class)).isZero();
    }

    private JsonNode indexAndWait() throws Exception {
        assertThat(post("/api/repos/" + repoId + "/index", "", KEY).statusCode()).isEqualTo(202);
        return waitWhileIndexing();
    }

    private JsonNode waitWhileIndexing() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            HttpResponse<String> response = get("/api/repos/" + repoId + "/status", VISITOR_A);
            JsonNode status = json.readTree(response.body());
            if (!"INDEXING".equals(status.get("indexStatus").asString()) || System.currentTimeMillis() > deadline) {
                return status;
            }
            Thread.sleep(100);
        }
    }

    private HttpResponse<String> get(String path, String visitor) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("X-Visitor-Id", visitor).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String visitor) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("X-Visitor-Id", visitor).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int countVectors() {
        return jdbc.queryForObject("select count(*) from vector_store_openai where metadata->>'repoId' = ?",
                Integer.class, repoId);
    }

    private int vectorsFor(String path) {
        return jdbc.queryForObject(
                "select count(*) from vector_store_openai where metadata->>'repoId' = ? and metadata->>'filePath' = ?",
                Integer.class, repoId, path);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> post(String path, String body, String apiKey) throws Exception {
        return post(path, body, apiKey, VISITOR_A);
    }

    private HttpResponse<String> post(String path, String body, String apiKey, String visitor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-Visitor-Id", visitor)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postWithHeader(String path, String body, String header, String value) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-Visitor-Id", VISITOR_A)
                .header(header, value)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
