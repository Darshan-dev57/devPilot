package devPilot.backend.services.ai;

/**
 * AI backends the server can use. Each gets its own vector table because embedding
 * dimensions differ (OpenAI 1536, Gemini 768, Ollama's nomic-embed-text 768).
 */
public enum AiProvider {
    GEMINI,
    OPENAI,
    OLLAMA;

    public static AiProvider parse(String value) {
        if (value == null || value.isBlank()) {
            return GEMINI;
        }
        try {
            return AiProvider.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unknown AI provider: " + value + " (use gemini, openai or ollama)");
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
        };
    }
}
