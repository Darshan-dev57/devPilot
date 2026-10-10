package devPilot.backend.services.ai;

/**
 * AI backends the server can use. Each embedding provider gets its own vector table
 * because embedding dimensions differ (OpenAI 1536, Gemini 768). OpenCode Zen and Claude
 * are chat only, for a visitor's own key.
 */
public enum AiProvider {
    GEMINI,
    OPENAI,
    OPENCODE,
    ANTHROPIC;

    /** Only Gemini and OpenAI have embeddings; OpenCode Zen and Claude are chat only. */
    public boolean embeds() {
        return this == GEMINI || this == OPENAI;
    }

    public static AiProvider parse(String value) {
        if (value == null || value.isBlank()) {
            return GEMINI;
        }
        try {
            String v = value.trim().toUpperCase();
            return AiProvider.valueOf(v.equals("CLAUDE") ? "ANTHROPIC" : v);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unknown AI provider: " + value + " (use gemini, openai, claude or opencode)");
        }
    }

    public String tableName() {
        return "vector_store_" + name().toLowerCase();
    }

    public String label() {
        return switch (this) {
            case GEMINI -> "Gemini";
            case OPENAI -> "OpenAI";
            case OPENCODE -> "OpenCode Zen";
            case ANTHROPIC -> "Claude";
        };
    }
}
