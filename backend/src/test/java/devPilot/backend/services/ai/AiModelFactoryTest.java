package devPilot.backend.services.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

/** Builds models with dummy keys without touching the network. */
class AiModelFactoryTest {

    private final AiModelFactory factory = new AiModelFactory(null, RestClient.builder());

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(factory, "openaiBaseUrl", "https://api.openai.com/v1");
        ReflectionTestUtils.setField(factory, "openaiChatModel", "gpt-4o-mini");
        ReflectionTestUtils.setField(factory, "geminiBaseUrl", "https://generativelanguage.googleapis.com");
        ReflectionTestUtils.setField(factory, "geminiKeyHeader", "x-goog-api-key");
        ReflectionTestUtils.setField(factory, "geminiChatModel", "gemini-3.5-flash-lite");
        ReflectionTestUtils.setField(factory, "ollamaBaseUrl", "http://localhost:11434");
        ReflectionTestUtils.setField(factory, "ollamaChatModel", "qwen2.5-coder:1.5b");
    }

    @Test
    void buildsOpenAiChatModel() {
        ChatModel model = factory.chatModel(AiProvider.OPENAI, "sk-test-dummy-key");
        assertEquals("gpt-4o-mini", model.getDefaultOptions().getModel());
    }

    @Test
    void buildsNativeGeminiChatModel() {
        ChatModel model = factory.chatModel(AiProvider.GEMINI, "AIza-test-dummy-key");
        assertInstanceOf(GeminiChatModel.class, model);
        assertEquals("gemini-3.5-flash-lite", model.getDefaultOptions().getModel());
    }

    @Test
    void buildsOllamaChatModelOnTheOpenAiCompatibleApi() {
        ChatModel model = factory.chatModel(AiProvider.OLLAMA, "unused");
        assertEquals("qwen2.5-coder:1.5b", model.getDefaultOptions().getModel());
    }

    @Test
    void providerTableNamesDiffer() {
        assertEquals("vector_store_openai", AiProvider.OPENAI.tableName());
        assertEquals("vector_store_gemini", AiProvider.GEMINI.tableName());
        assertEquals("vector_store_ollama", AiProvider.OLLAMA.tableName());
    }
}
