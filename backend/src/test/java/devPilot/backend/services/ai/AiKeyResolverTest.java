package devPilot.backend.services.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import devPilot.backend.exceptions.ServiceUnavailableException;

class AiKeyResolverTest {

    @Test
    void geminiIsTheDefaultWhenItsKeyIsSet() {
        var key = new AiKeyResolver("AIza-test", "sk-test", "").requireKey();
        assertThat(key.provider()).isEqualTo(AiProvider.GEMINI);
        assertThat(key.apiKey()).isEqualTo("AIza-test");
    }

    @Test
    void openAiIsUsedWhenOnlyItsKeyIsSet() {
        assertThat(new AiKeyResolver("", "sk-test", "").requireKey().provider()).isEqualTo(AiProvider.OPENAI);
    }

    @Test
    void explicitProviderWins() {
        assertThat(new AiKeyResolver("AIza-test", "sk-test", "openai").requireKey().provider()).isEqualTo(AiProvider.OPENAI);
    }

    @Test
    void noKeyMeansAClearServiceError() {
        var resolver = new AiKeyResolver(" ", "", "");
        assertThat(resolver.configured()).isFalse();
        assertThatThrownBy(resolver::requireKey)
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("GEMINI_API_KEY");
    }

    @Test
    void openCodeAnswersWhileGeminiEmbeds() {
        var resolver = new AiKeyResolver("AIza-test", "", "zen-key", "opencode", "");
        assertThat(resolver.requireKey().provider()).isEqualTo(AiProvider.GEMINI);
        assertThat(resolver.requireChatKey()).isEqualTo(new AiKeyResolver.UserAiKey(AiProvider.OPENCODE, "zen-key"));
        assertThat(resolver.configured()).isTrue();
    }

    @Test
    void openCodeWithoutItsKeyIsAClearServiceError() {
        var resolver = new AiKeyResolver("AIza-test", "", "", "opencode", "");
        assertThat(resolver.configured()).isFalse();
        assertThatThrownBy(resolver::requireChatKey).hasMessageContaining("OPENCODE_API_KEY");
    }

    @Test
    void openCodeCanEmbedWithOllamaLocally() {
        var resolver = new AiKeyResolver("", "", "zen-key", "opencode", "ollama");
        assertThat(resolver.requireKey().provider()).isEqualTo(AiProvider.OLLAMA);
        assertThatThrownBy(() -> new AiKeyResolver("", "", "zen-key", "opencode", "opencode").provider())
                .hasMessageContaining("no embeddings");
    }
}
