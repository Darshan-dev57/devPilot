package devPilot.backend.services.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.exceptions.ServiceUnavailableException;

/**
 * Which AI keys a request uses.
 *
 * <p>By default everything runs on the server owner's Gemini key (GEMINI_API_KEY, free
 * tier; several comma-separated keys act as fallbacks), inside the daily free limits. OPENAI_API_KEY works as the server key too.
 * A visitor who wants more can send their own Gemini key (indexing and answers) and/or a
 * key for answers from OpenAI, Claude or OpenCode Zen; those requests skip the daily
 * limit. Indexing prefers Gemini (the visitor's, else the server's), so a repo indexed
 * once stays searchable for everyone. When neither exists, a visitor's own OpenAI key
 * indexes with OpenAI embeddings instead. Each repo records which store its vectors are
 * in, and questions are searched in that store only.
 */
@Component
public class AiKeyResolver {

    public static final String NOT_CONFIGURED =
            "This site has no shared AI key right now. Add your own under 'Use your own key': "
                    + "a free Gemini key (aistudio.google.com/apikey) or an OpenAI key.";

    public static final String NEEDS_INDEX_KEY =
            "Indexing needs a Gemini or OpenAI key, and this site has no shared one right now. "
                    + "Add a free Gemini key under 'Use your own key' (aistudio.google.com/apikey).";

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
        // GEMINI_API_KEY may list several keys ("k1,k2"); the first is the main one and
        // GeminiKeyPool falls back to the others when Google stops it.
        this.geminiKey = GeminiKeyPool.parse(geminiKey).stream().findFirst().orElse("");
        this.openaiKey = clean(openaiKey);
        this.preferred = clean(preferred);
        this.embeddingPreferred = clean(embeddingPreferred);
        if (!this.preferred.isEmpty() && !AiProvider.parse(this.preferred).embeds()) {
            throw new IllegalStateException("AI_PROVIDER=" + this.preferred
                    + " is not supported on the server. Claude and OpenCode Zen work only as a visitor's own key.");
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

    /**
     * Key for indexing: the visitor's Gemini key, else the server's key, else the visitor's
     * own OpenAI key (OpenAI embeddings). Claude and OpenCode Zen have no embeddings.
     */
    public UserAiKey embeddingKey(VisitorKeys visitor) {
        if (visitor.hasGemini() && (provider() == AiProvider.GEMINI || !configured())) {
            return UserAiKey.visitor(AiProvider.GEMINI, visitor.gemini());
        }
        if (configured()) {
            return requireKey();
        }
        if (visitor.hasOpenAiKey()) {
            return UserAiKey.visitor(AiProvider.OPENAI, visitor.chatKey());
        }
        throw new ServiceUnavailableException(visitor.any() ? NEEDS_INDEX_KEY : NOT_CONFIGURED);
    }

    /**
     * Key for searching a repo's vectors: it must be the provider whose embeddings built
     * that index (store = the repo's embedding provider), since the two stores never mix.
     */
    public UserAiKey searchKey(String store, VisitorKeys visitor) {
        if (store == null || store.isBlank()) {
            return embeddingKey(visitor);
        }
        AiProvider provider = AiProvider.parse(store);
        if (provider == AiProvider.GEMINI) {
            if (visitor.hasGemini()) {
                return UserAiKey.visitor(AiProvider.GEMINI, visitor.gemini());
            }
            if (!geminiKey.isEmpty()) {
                return new UserAiKey(AiProvider.GEMINI, geminiKey);
            }
            throw new BadRequestException("This repo was indexed with Gemini. To chat with it, add a free Gemini key "
                    + "under 'Use your own key' (aistudio.google.com/apikey), or index it again with your OpenAI key.");
        }
        if (!openaiKey.isEmpty()) {
            return new UserAiKey(AiProvider.OPENAI, openaiKey);
        }
        if (visitor.hasOpenAiKey()) {
            return UserAiKey.visitor(AiProvider.OPENAI, visitor.chatKey());
        }
        throw new BadRequestException("This repo was indexed with an OpenAI key. To chat with it, add an OpenAI key "
                + "under 'Use your own key', or index it again with a Gemini key.");
    }

    /**
     * Chat key for this request: the visitor's chosen provider and key (OpenAI, Claude or
     * OpenCode Zen), else their Gemini key, else the server's.
     */
    public UserAiKey chatKey(VisitorKeys visitor) {
        String model = visitor.chatModel().isEmpty() ? null : visitor.chatModel();
        if (visitor.hasChatKey()) {
            return new UserAiKey(visitor.chatProviderEnum(), visitor.chatKey(), true, model);
        }
        if (visitor.hasGemini()) {
            boolean geminiChosen = "gemini".equals(visitor.chatProvider());
            return new UserAiKey(AiProvider.GEMINI, visitor.gemini(), true, geminiChosen ? model : null);
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
