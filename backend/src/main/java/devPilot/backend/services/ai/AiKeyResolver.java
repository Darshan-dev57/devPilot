package devPilot.backend.services.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import devPilot.backend.exceptions.ServiceUnavailableException;

/**
 * Which AI keys a request uses.
 *
 * <p>By default everything runs on the server owner's one Gemini key (GEMINI_API_KEY,
 * free tier), inside the daily free limits. OPENAI_API_KEY works as the server key too.
 * A visitor who wants more can send their own Gemini key and/or their own OpenCode Zen
 * key; those requests run on the visitor's key and skip the daily limit. OpenCode Zen is
 * a visitor-key option only: its free models refuse calls from outside the OpenCode app.
 */
@Component
public class AiKeyResolver {

    public static final String NOT_CONFIGURED =
            "This DevPilot server has no AI key configured yet. The site owner needs to set GEMINI_API_KEY.";

    private final String geminiKey;
    private final String openaiKey;
    private final String preferred;
    private final String embeddingPreferred;

    public AiKeyResolver(String geminiKey, String openaiKey, String preferred) {
        this(geminiKey, openaiKey, preferred, "");
    }

    @Autowired
    public AiKeyResolver(
            @Value("${app.ai.gemini.api-key:}") String geminiKey,
            @Value("${app.ai.openai.api-key:}") String openaiKey,
            @Value("${app.ai.provider:}") String preferred,
            @Value("${app.ai.embedding-provider:}") String embeddingPreferred) {
        this.geminiKey = clean(geminiKey);
        this.openaiKey = clean(openaiKey);
        this.preferred = clean(preferred);
        this.embeddingPreferred = clean(embeddingPreferred);
        if (!this.preferred.isEmpty() && !AiProvider.parse(this.preferred).embeds()) {
            throw new IllegalStateException("AI_PROVIDER=" + this.preferred
                    + " is not supported on the server. OpenCode Zen works only as a visitor's own key.");
        }
    }

    /** The server's key for embeddings (indexing and retrieval). */
    public UserAiKey requireKey() {
        AiProvider provider = provider();
        String key = provider == AiProvider.GEMINI ? geminiKey : openaiKey;
        if (key.isEmpty()) {
            throw new ServiceUnavailableException(NOT_CONFIGURED);
        }
        return new UserAiKey(provider, key);
    }

    /** The server's key for writing answers: the same provider and key as embeddings. */
    public UserAiKey requireChatKey() {
        return requireKey();
    }

    /** Embedding key for this request: the visitor's Gemini key when given, else the server's. */
    public UserAiKey embeddingKey(VisitorKeys visitor) {
        if (visitor.hasGemini() && provider() == AiProvider.GEMINI) {
            return UserAiKey.visitor(AiProvider.GEMINI, visitor.gemini());
        }
        return requireKey();
    }

    /** Chat key for this request: the visitor's OpenCode key, else their Gemini key, else the server's. */
    public UserAiKey chatKey(VisitorKeys visitor) {
        if (visitor.hasOpencode()) {
            return new UserAiKey(AiProvider.OPENCODE, visitor.opencode(), true,
                    visitor.opencodeModel().isEmpty() ? null : visitor.opencodeModel());
        }
        if (visitor.hasGemini()) {
            return UserAiKey.visitor(AiProvider.GEMINI, visitor.gemini());
        }
        return requireChatKey();
    }

    /** The embedding provider: AI_EMBEDDING_PROVIDER, else Gemini unless only an OpenAI key is set. */
    public AiProvider provider() {
        if (!embeddingPreferred.isEmpty()) {
            AiProvider provider = AiProvider.parse(embeddingPreferred);
            if (!provider.embeds()) {
                throw new IllegalStateException("AI_EMBEDDING_PROVIDER cannot be " + provider.label()
                        + ": it has no embeddings. Use gemini or openai.");
            }
            return provider;
        }
        if (!preferred.isEmpty() && AiProvider.parse(preferred).embeds()) {
            return AiProvider.parse(preferred);
        }
        if (geminiKey.isEmpty() && !openaiKey.isEmpty()) {
            return AiProvider.OPENAI;
        }
        return AiProvider.GEMINI;
    }

    /** The provider that writes answers with the server's key. */
    public AiProvider chatProvider() {
        return provider();
    }

    public boolean configured() {
        return !(provider() == AiProvider.GEMINI ? geminiKey : openaiKey).isEmpty();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * A key, where it came from, and an optional model override (a visitor's OpenCode Zen
     * model). toString never prints the key itself.
     */
    public record UserAiKey(AiProvider provider, String apiKey, boolean visitor, String model) {

        public UserAiKey(AiProvider provider, String apiKey) {
            this(provider, apiKey, false, null);
        }

        static UserAiKey visitor(AiProvider provider, String apiKey) {
            return new UserAiKey(provider, apiKey, true, null);
        }

        @Override
        public String toString() {
            return "UserAiKey[" + provider + (visitor ? ", visitor key" : ", server key") + "]";
        }
    }
}
