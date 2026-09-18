package devPilot.backend.services.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builds per-user models with a dummy key without touching the network.
 */
class AiModelFactoryTest {

    private final AiModelFactory factory =
            new AiModelFactory(null, org.springframework.web.client.RestClient.builder());

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(factory, "openaiBaseUrl", "https://api.openai.com");
        ReflectionTestUtils.setField(factory, "openaiChatModel", "gpt-4o-mini");
        ReflectionTestUtils.setField(factory, "openaiEmbeddingModel", "text-embedding-3-small");
        ReflectionTestUtils.setField(factory, "openaiEmbeddingDimensions", 1536);
        ReflectionTestUtils.setField(factory, "geminiBaseUrl",
                "https://generativelanguage.googleapis.com/v1beta/openai/");
        ReflectionTestUtils.setField(factory, "geminiChatModel", "gemini-3.8-flash");
        ReflectionTestUtils.setField(factory, "geminiEmbeddingModel", "gemini-embedding-001");
        ReflectionTestUtils.setField(factory, "geminiEmbeddingDimensions", 768);
    }

    @Test
    void buildsOpenAiChatModelForUserKey() {
        ChatModel model = factory.chatModel(UUID.randomUUID(), AiProvider.OPENAI, "sk-test-dummy-key");

        assertNotNull(model);
        assertEquals("gpt-4o-mini", model.getDefaultOptions().getModel());
    }

    @Test
    void buildsGeminiChatModelForUserKey() {
        ChatModel model = factory.chatModel(UUID.randomUUID(), AiProvider.GEMINI, "AIza-test-dummy-key");

        assertNotNull(model);
        assertEquals("gemini-3.8-flash", model.getDefaultOptions().getModel());
    }

    @Test
    void providerTableNamesDiffer() {
        assertEquals("vector_store_openai", AiProvider.OPENAI.tableName());
        assertEquals("vector_store_gemini", AiProvider.GEMINI.tableName());
    }
}
