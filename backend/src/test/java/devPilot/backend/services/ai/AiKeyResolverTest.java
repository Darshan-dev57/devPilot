package devPilot.backend.services.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import devPilot.backend.exceptions.BadRequestException;
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
                .hasMessageContaining("Use your own key")
                .hasMessageNotContaining("site owner");
    }

    @Test
    void aVisitorsGeminiKeyIsUsedForEmbeddingsAndAnswers() {
        var resolver = new AiKeyResolver("server-key", "", "");
        var keys = VisitorKeys.geminiOnly(" visitor-gemini ");
        assertThat(resolver.embeddingKey(keys)).isEqualTo(new AiKeyResolver.UserAiKey(AiProvider.GEMINI, "visitor-gemini", true, null));
        assertThat(resolver.chatKey(keys).apiKey()).isEqualTo("visitor-gemini");
        assertThat(resolver.chatKey(VisitorKeys.NONE)).isEqualTo(new AiKeyResolver.UserAiKey(AiProvider.GEMINI, "server-key"));
    }

    @Test
    void aVisitorsOpenCodeKeyAnswersWhileTheServerEmbeds() {
        var resolver = new AiKeyResolver("server-key", "", "");
        var keys = new VisitorKeys(null, "opencode", "zen-key", "qwen3.8-max");
        var chat = resolver.chatKey(keys);
        assertThat(chat.provider()).isEqualTo(AiProvider.OPENCODE);
        assertThat(chat.visitor()).isTrue();
        assertThat(chat.model()).isEqualTo("qwen3.8-max");
        assertThat(resolver.embeddingKey(keys).visitor()).isFalse();
    }

    @Test
    void keysNeverShowUpInToString() {
        var resolver = new AiKeyResolver("server-secret", "", "");
        assertThat(resolver.chatKey(new VisitorKeys("visitor-secret", "claude", "claude-secret", null)).toString()).doesNotContain("secret");
        assertThat(resolver.requireKey().toString()).doesNotContain("secret");
        assertThat(new VisitorKeys("visitor-secret", "openai", "sk-secret", "m").toString()).doesNotContain("secret");
    }

    @Test
    void openCodeCannotBeTheServersProvider() {
        assertThatThrownBy(() -> new AiKeyResolver("k", "", "opencode"))
                .hasMessageContaining("visitor's own key");
    }

    @Test
    void claudeOrOpenAiAnswerWhileEmbeddingsStayOnGemini() {
        var resolver = new AiKeyResolver("server-key", "", "");
        var claude = new VisitorKeys("visitor-gemini", "claude", "sk-ant-x", "");
        assertThat(resolver.chatKey(claude)).isEqualTo(new AiKeyResolver.UserAiKey(AiProvider.ANTHROPIC, "sk-ant-x", true, null));
        assertThat(resolver.embeddingKey(claude).apiKey()).isEqualTo("visitor-gemini");
        var openai = new VisitorKeys(null, "openai", "sk-x", "gpt-6-luna");
        assertThat(resolver.chatKey(openai).provider()).isEqualTo(AiProvider.OPENAI);
        assertThat(resolver.embeddingKey(openai)).isEqualTo(new AiKeyResolver.UserAiKey(AiProvider.GEMINI, "server-key"));
    }

    @Test
    void aChatProviderWithoutItsKeyFallsBackToTheFreeServerKey() {
        var resolver = new AiKeyResolver("server-key", "", "");
        assertThat(resolver.chatKey(new VisitorKeys(null, "claude", "", null)).visitor()).isFalse();
    }

    @Test
    void withNoGeminiKeyAnywhereAVisitorsOpenAiKeyIndexes() {
        var resolver = new AiKeyResolver("", "", "");
        var openai = new VisitorKeys(null, "openai", "sk-visitor", "gpt-6-luna");
        assertThat(resolver.embeddingKey(openai)).isEqualTo(new AiKeyResolver.UserAiKey(AiProvider.OPENAI, "sk-visitor", true, null));
        // A Gemini key next to it still wins, so the repo lands in the Gemini store.
        var both = new VisitorKeys("visitor-gemini", "openai", "sk-visitor", null);
        assertThat(resolver.embeddingKey(both).provider()).isEqualTo(AiProvider.GEMINI);
    }

    @Test
    void theServersGeminiKeyStillIndexesForAnOpenAiVisitor() {
        var resolver = new AiKeyResolver("server-key", "", "");
        var openai = new VisitorKeys(null, "openai", "sk-visitor", null);
        assertThat(resolver.embeddingKey(openai)).isEqualTo(new AiKeyResolver.UserAiKey(AiProvider.GEMINI, "server-key"));
    }

    @Test
    void claudeAloneCannotIndexAndSaysWhatToAdd() {
        var resolver = new AiKeyResolver("", "", "");
        assertThatThrownBy(() -> resolver.embeddingKey(new VisitorKeys(null, "claude", "sk-ant-x", null)))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage(AiKeyResolver.NEEDS_INDEX_KEY);
    }

    @Test
    void questionsAreSearchedInTheStoreTheRepoWasIndexedInto() {
        var openai = new VisitorKeys(null, "openai", "sk-visitor", null);
        var noServer = new AiKeyResolver("", "", "");
        assertThat(noServer.searchKey("OPENAI", openai)).isEqualTo(new AiKeyResolver.UserAiKey(AiProvider.OPENAI, "sk-visitor", true, null));
        // Even with a server Gemini key, an OpenAI-indexed repo is searched with OpenAI.
        assertThat(new AiKeyResolver("server-key", "", "").searchKey("OPENAI", openai).provider()).isEqualTo(AiProvider.OPENAI);
        assertThat(new AiKeyResolver("server-key", "", "").searchKey("GEMINI", openai)).isEqualTo(new AiKeyResolver.UserAiKey(AiProvider.GEMINI, "server-key"));
        assertThatThrownBy(() -> noServer.searchKey("OPENAI", VisitorKeys.geminiOnly("g")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("indexed with an OpenAI key");
        assertThatThrownBy(() -> noServer.searchKey("GEMINI", openai))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("indexed with Gemini");
    }
}
