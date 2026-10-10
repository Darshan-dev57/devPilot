package devPilot.backend.services.ai;

/**
 * Keys a visitor chose to use instead of the server's free allowance, sent per request:
 * <ul>
 *   <li>X-Gemini-Key: their Gemini key, for indexing (embeddings) and, by default, answers.</li>
 *   <li>X-Chat-Provider, X-Chat-Key, X-Chat-Model: another provider for answers
 *       (openai, claude or opencode), its key and model.</li>
 * </ul>
 * They live only for that request (or that index job) and are never stored or logged.
 */
public record VisitorKeys(String gemini, String chatProvider, String chatKey, String chatModel) {

    public static final String GEMINI_HEADER = "X-Gemini-Key";
    public static final String CHAT_PROVIDER_HEADER = "X-Chat-Provider";
    public static final String CHAT_KEY_HEADER = "X-Chat-Key";
    public static final String CHAT_MODEL_HEADER = "X-Chat-Model";
    public static final VisitorKeys NONE = new VisitorKeys(null, null, null, null);

    public VisitorKeys {
        gemini = clean(gemini);
        chatProvider = clean(chatProvider).toLowerCase();
        chatKey = clean(chatKey);
        chatModel = clean(chatModel);
    }

    public static VisitorKeys geminiOnly(String gemini) {
        return new VisitorKeys(gemini, null, null, null);
    }

    public boolean hasGemini() {
        return !gemini.isEmpty();
    }

    /** True when answers should come from the visitor's own non-Gemini key. */
    public boolean hasChatKey() {
        return !chatKey.isEmpty() && !chatProvider.isEmpty() && !"gemini".equals(chatProvider);
    }

    public AiProvider chatProviderEnum() {
        return AiProvider.parse(chatProvider);
    }

    public boolean any() {
        return hasGemini() || hasChatKey();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        return "VisitorKeys[gemini=" + (hasGemini() ? "set" : "none") + ", chat=" + (hasChatKey() ? chatProvider : "none") + "]";
    }
}
