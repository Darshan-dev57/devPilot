package devPilot.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.Repository;
import devPilot.backend.entity.User;
import devPilot.backend.repository.ChatMessageRepository;
import devPilot.backend.repository.ChatSessionRepository;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.repository.UserRepository;
import devPilot.backend.security.AppUserPrincipal;
import devPilot.backend.services.UserService;
import devPilot.backend.services.ai.AiKeyResolver;
import devPilot.backend.services.ai.AiModelFactory;
import devPilot.backend.services.ai.CodeContextRetriever;
import devPilot.backend.services.ai.RagSettings;

/**
 * Real end-to-end AI test against the Gemini free tier (OpenAI-compatible endpoint).
 *
 * <p>Runs ONLY when GEMINI_API_KEY is set, e.g.:
 * GEMINI_API_KEY=... ./mvnw test -Dtest=GeminiE2ETest
 * Needs postgres on localhost:5433 and Gemini settings in application.properties.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
class GeminiE2ETest {

    @Autowired MockMvc mockMvc;
    @Autowired tools.jackson.databind.json.JsonMapper jsonMapper;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired RepositoryRepository repositoryRepository;
    @Autowired ChatSessionRepository chatSessionRepository;
    @Autowired ChatMessageRepository chatMessageRepository;
    @Autowired AiModelFactory aiModelFactory;
    @Autowired AiKeyResolver aiKeyResolver;
    @Autowired CodeContextRetriever retriever;

    private UUID userId;
    private UUID repoId;

    @Test
    void fullAiFlowWithUserKey() throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");

        // 1. User + key save/decrypt roundtrip
        User user = userRepository.save(User.builder()
                .githubId(999999001L)
                .githubUsername("e2e-bot")
                .displayName("E2E Bot")
                .accessToken("dummy")
                .build());
        userId = user.getId();
        userService.saveOpenAiKey(userId, apiKey);
        assertTrue(userService.hasOpenAiKey(userService.requiredById(userId)));
        assertEquals(apiKey, aiKeyResolver.requireDecryptedKey(userId));

        var auth = new UsernamePasswordAuthenticationToken(
                new AppUserPrincipal(userService.requiredById(userId), Map.of()),
                null,
                List.of(() -> "ROLE_USER"));

        // 2. READY repo owned by the user
        Repository repo = repositoryRepository.save(Repository.builder()
                .userId(userId)
                .githubRepoId(888888001L)
                .owner("e2e")
                .name("demo")
                .fullName("e2e/demo")
                .isPrivate(false)
                .defaultBranch("main")
                .indexStatus(IndexStatus.READY)
                .build());
        repoId = repo.getId();

        // 3. Real embedding + store with the USER key
        VectorStore store = aiModelFactory.vectorStore(userId, apiKey);
        store.add(List.of(new Document(
                "DevPilot is a GitHub-connected AI code assistant. It indexes repositories and answers questions with file citations.",
                Map.of(
                        RagSettings.METADATA_REPO_ID, repoId.toString(),
                        "filePath", "README.md",
                        "language", "markdown"))));

        // 4. Real retrieval
        var retrieved = retriever.retrieve(store, repoId, "What is DevPilot?");
        assertTrue(retrieved.contextText().contains("DevPilot"),
                "retrieved context should mention DevPilot, got: " + retrieved.contextText());

        // 5. Real chat session + streamed reply over HTTP (SSE)
        MvcResult sessionResult = mockMvc.perform(post("/api/chat/sessions")
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryId\":\"" + repoId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andReturn();
        String sessionId = jsonMapper.readValue(
                sessionResult.getResponse().getContentAsString(), Map.class).get("id").toString();

        MvcResult streamStart = mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"What is DevPilot? Answer in one short sentence.\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult streamResult = mockMvc.perform(asyncDispatch(streamStart))
                .andExpect(status().isOk())
                .andReturn();
        String sse = streamResult.getResponse().getContentAsString();
        assertTrue(sse.contains("DevPilot"), "streamed reply should mention DevPilot, got:\n" + sse);

        // 6. Assistant message persisted
        List<ChatMessage> messages = chatMessageRepository
                .findBySessionIdOrderByCreatedAtAsc(UUID.fromString(sessionId));
        assertEquals(2, messages.size());
        assertTrue(messages.get(1).getContent().contains("DevPilot"));
    }

    @AfterEach
    void cleanUp() {
        try {
            if (userId != null) {
                String key = System.getenv("GEMINI_API_KEY");
                if (key != null && repoId != null) {
                    var filter = new FilterExpressionBuilder()
                            .eq(RagSettings.METADATA_REPO_ID, repoId.toString()).build();
                    aiModelFactory.vectorStore(userId, key).delete(filter);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            if (repoId != null) {
                chatSessionRepository.findAll().stream()
                        .filter(s -> s.getRepositoryId().equals(repoId))
                        .forEach(s -> {
                            chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(s.getId())
                                    .forEach(chatMessageRepository::delete);
                            chatSessionRepository.delete(s);
                        });
                repositoryRepository.deleteById(repoId);
            }
            if (userId != null) {
                userRepository.deleteById(userId);
            }
        } catch (Exception ignored) {
        }
    }
}
