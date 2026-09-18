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
        ReflectionTestUtils.setField(factory, "baseUrl", "https://api.openai.com");
        ReflectionTestUtils.setField(factory, "chatModelName", "gpt-4o-mini");
        ReflectionTestUtils.setField(factory, "embeddingModelName", "text-embedding-3-small");
        ReflectionTestUtils.setField(factory, "embeddingDimensions", 1536);
    }

    @Test
    void buildsChatModelForUserKey() {
        ChatModel model = factory.chatModel(UUID.randomUUID(), "sk-test-dummy-key");

        assertNotNull(model);
        assertEquals("gpt-4o-mini", model.getDefaultOptions().getModel());
    }
}
