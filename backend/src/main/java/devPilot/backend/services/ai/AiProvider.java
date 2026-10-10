package devPilot.backend.services.ai;

/**
 * AI backends the server can use. Each embedding provider gets its own vector table
 * because embedding dimensions differ (OpenAI 1536, Gemini 768, Ollama's nomic-embed-text
 * 768). OpenCode Zen is chat only: it has no embeddings endpoint.
 */
public enum AiProvider {
    GEMINI,
    OPENAI,
    OLLAMA,
    OPENCODE;

    public boolean embeds() {
        return this != OPENCODE;
    }

    public static AiProvider parse(String value) {
        if (value == null || value.isBlank()) {
            return GEMINI;
        }
        try {
            return AiProvider.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unknown AI provider: " + value + " (use gemini, openai, ollama or opencode)");
        }
    }

    public String tableName() {
        return "vector_store_" + name().toLowerCase();
    }

    public String label() {
        return switch (this) {
            case GEMINI -> "Gemini";
            case OPENAI -> "OpenAI";
            case OLLAMA -> "Ollama";
            case OPENCODE -> "OpenCode Zen";
        };
    }
}
