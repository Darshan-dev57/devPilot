package devPilot.backend.services.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import devPilot.backend.exceptions.ServiceUnavailableException;

/**
 * The AI keys this server uses, set by the operator in environment variables.
 * Visitors never paste a key.
 *
 * <p>GEMINI_API_KEY is the default path (Google's Gemini API has a free tier).
 * OPENAI_API_KEY works too, and AI_PROVIDER=ollama needs no key at all.
 * AI_PROVIDER=opencode answers with an OpenCode Zen model (OPENCODE_API_KEY) while
 * embeddings stay on Gemini, or on AI_EMBEDDING_PROVIDER if that is set.
 */
@Component
public class AiKeyResolver {

    public static final String NOT_CONFIGURED =
            "This DevPilot server has no AI key configured yet. The site owner needs to set GEMINI_API_KEY (or OPENAI_API_KEY, or AI_PROVIDER=ollama).";
    public static final String NO_OPENCODE_KEY =
            "This DevPilot server is set to answer with OpenCode Zen but has no key. The site owner needs to set OPENCODE_API_KEY.";

    private final String geminiKey;
    private final String openaiKey;
    private final String opencodeKey;
    private final String preferred;
    private final String embeddingPreferred;

    public AiKeyResolver(String geminiKey, String openaiKey, String preferred) {
        this(geminiKey, openaiKey, "", preferred, "");
    }

    @Autowired
    public AiKeyResolver(
            @Value("${app.ai.gemini.api-key:}") String geminiKey,
            @Value("${app.ai.openai.api-key:}") String openaiKey,
            @Value("${app.ai.opencode.api-key:}") String opencodeKey,
            @Value("${app.ai.provider:}") String preferred,
            @Value("${app.ai.embedding-provider:}") String embeddingPreferred) {
        this.geminiKey = clean(geminiKey);
        this.openaiKey = clean(openaiKey);
        this.opencodeKey = clean(opencodeKey);
        this.preferred = clean(preferred);
        this.embeddingPreferred = clean(embeddingPreferred);
    }

    /** The key for embeddings (indexing and retrieval). */
    public UserAiKey requireKey() {
        AiProvider provider = provider();
        if (provider == AiProvider.OLLAMA) {
            return new UserAiKey(provider, "ollama");
        }
        String key = provider == AiProvider.GEMINI ? geminiKey : openaiKey;
        if (key.isEmpty()) {
            throw new ServiceUnavailableException(NOT_CONFIGURED);
        }
        return new UserAiKey(provider, key);
    }

    /** The key for writing answers. Same as {@link #requireKey()} unless chat runs on OpenCode Zen. */
    public UserAiKey requireChatKey() {
        if (chatProvider() != AiProvider.OPENCODE) {
            return requireKey();
        }
        if (opencodeKey.isEmpty()) {
            throw new ServiceUnavailableException(NO_OPENCODE_KEY);
        }
        return new UserAiKey(AiProvider.OPENCODE, opencodeKey);
    }

    /** The embedding provider: the explicit setting, else whichever key is present (Gemini first). */
    public AiProvider provider() {
        if (!embeddingPreferred.isEmpty()) {
            return embeddingProvider(AiProvider.parse(embeddingPreferred));
        }
        if (!preferred.isEmpty() && AiProvider.parse(preferred).embeds()) {
            return AiProvider.parse(preferred);
        }
        if (!preferred.isEmpty()) {
            return AiProvider.GEMINI; // opencode: embeddings default to Gemini's free tier
        }
        if (!geminiKey.isEmpty()) {
            return AiProvider.GEMINI;
        }
        return openaiKey.isEmpty() ? AiProvider.GEMINI : AiProvider.OPENAI;
    }

    /** The provider that writes answers. */
    public AiProvider chatProvider() {
        if (!preferred.isEmpty() && AiProvider.parse(preferred) == AiProvider.OPENCODE) {
            return AiProvider.OPENCODE;
        }
        return provider();
    }

    public boolean configured() {
        AiProvider provider = provider();
        boolean embeddings = provider == AiProvider.OLLAMA
                || !(provider == AiProvider.GEMINI ? geminiKey : openaiKey).isEmpty();
        return embeddings && (chatProvider() != AiProvider.OPENCODE || !opencodeKey.isEmpty());
    }

    private static AiProvider embeddingProvider(AiProvider provider) {
        if (!provider.embeds()) {
            throw new IllegalStateException("AI_EMBEDDING_PROVIDER cannot be " + provider.label()
                    + ": it has no embeddings. Use gemini, openai or ollama.");
        }
        return provider;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public record UserAiKey(AiProvider provider, String apiKey) {
    }
}
