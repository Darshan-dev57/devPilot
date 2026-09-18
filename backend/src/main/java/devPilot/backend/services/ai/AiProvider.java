package devPilot.backend.services.ai;

/**
 * AI providers a user can bring their own key for.
 * Each provider gets its own vector table because embedding dimensions differ.
 */
public enum AiProvider {
    OPENAI,
    GEMINI;

    public static AiProvider parse(String value) {
        if (value == null) {
            return OPENAI;
        }
        try {
            return AiProvider.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new devPilot.backend.exceptions.BadRequestException(
                    "Unknown AI provider: " + value + " (use openai or gemini)");
        }
    }

    public String tableName() {
        return "vector_store_" + name().toLowerCase();
    }
}
