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
 * real HTTP: add a repo, full index, chat over SSE, then push commits and check that only
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

        assertThat(post("/api/repos/" + repoId + "/index", "", null).statusCode())
                .as("indexing without a key is refused").isEqualTo(400);

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
    void chatWithTheOtherProviderGetsAClearError() throws Exception {
        HttpResponse<String> session = post("/api/chat/sessions", "{\"repositoryId\":\"" + repoId + "\"}", null);
        String sessionId = json.readTree(session.body()).get("id").asString();
        HttpRequest request = HttpRequest.newBuilder(uri("/api/chat/sessions/" + sessionId + "/messages"))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", "AIza-gemini")
                .header("X-AI-Provider", "gemini")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"hi\"}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("indexed with OpenAI");
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
    void aRejectedKeyIsRefusedUpFrontAndTheExistingIndexSurvives() throws Exception {
        int before = countVectors();
        GITHUB.commit(Map.of("src/A.java", "class A {}\n"));
        HttpRequest request = HttpRequest.newBuilder(uri("/api/repos/" + repoId + "/index"))
                .header("X-Api-Key", "bad-key").header("X-AI-Provider", "openai")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).get("message").asString())
                .isEqualTo("Your OpenAI API key was rejected. Check it in Settings.");
        assertThat(countVectors()).isEqualTo(before);
        assertThat(waitWhileIndexing().get("indexStatus").asString()).isEqualTo("READY");
    }

    private JsonNode indexAndWait() throws Exception {
        assertThat(post("/api/repos/" + repoId + "/index", "", KEY).statusCode()).isEqualTo(202);
        return waitWhileIndexing();
    }

    private JsonNode waitWhileIndexing() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/repos/" + repoId + "/status")).build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode status = json.readTree(response.body());
            if (!"INDEXING".equals(status.get("indexStatus").asString()) || System.currentTimeMillis() > deadline) {
                return status;
            }
            Thread.sleep(100);
        }
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
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null) {
            request.header("X-Api-Key", apiKey).header("X-AI-Provider", "openai");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
