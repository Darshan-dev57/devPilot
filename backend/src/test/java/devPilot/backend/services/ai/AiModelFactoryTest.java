package devPilot.backend.services.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

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
        ReflectionTestUtils.setField(factory, "opencodeBaseUrl", "https://opencode.ai/zen/v1");
        ReflectionTestUtils.setField(factory, "opencodeChatModel", "deepseek-v4.1-flash");
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
    void aVisitorsOpenCodeModelChoiceIsUsedAndNotCached() {
        var key = new AiKeyResolver.UserAiKey(AiProvider.OPENCODE, "zen-dummy", true, "qwen3.8-max");
        ChatModel first = factory.chatModel(key);
        assertEquals("qwen3.8-max", first.getDefaultOptions().getModel());
        assertNotSame(first, factory.chatModel(key));
        var noModel = new AiKeyResolver.UserAiKey(AiProvider.OPENCODE, "zen-dummy", true, null);
        assertEquals("deepseek-v4.1-flash", factory.chatModel(noModel).getDefaultOptions().getModel());
    }

    @Test
    void providerTableNamesDiffer() {
        assertEquals("vector_store_openai", AiProvider.OPENAI.tableName());
        assertEquals("vector_store_gemini", AiProvider.GEMINI.tableName());
    }
}
