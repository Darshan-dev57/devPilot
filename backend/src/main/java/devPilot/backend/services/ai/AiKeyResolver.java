package devPilot.backend.services.ai;

import org.springframework.stereotype.Component;

import devPilot.backend.exceptions.BadRequestException;

@Component
public class AiKeyResolver {

    public static final String MISSING_KEY_MESSAGE =
            "Add your AI API key in Settings to use AI features";

    /**
     * Resolves the provider for a request. The client states the provider explicitly in the
     * X-AI-Provider header, which is authoritative: the provider decides which base URL,
     * model and vector table to use, and getting it wrong sends a Gemini key to OpenAI.
     *
     * <p>The key-prefix fallback only applies to older clients that predate the header.
     */
    public UserAiKey requireKey(String apiKey, String providerHeader) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BadRequestException(MISSING_KEY_MESSAGE);
        }
        if (providerHeader != null && !providerHeader.isBlank()) {
            return new UserAiKey(AiProvider.parse(providerHeader), apiKey);
        }
        return new UserAiKey(inferFromKey(apiKey), apiKey);
    }

    /**
     * Best-effort provider guess for clients that do not send X-AI-Provider. Google issues
     * Gemini keys under more than one prefix (AIza... and AQ...), so both are checked.
     * Anything unrecognised is assumed to be OpenAI, which is the historical default.
     */
    private static AiProvider inferFromKey(String apiKey) {
        if (apiKey.startsWith("AIza") || apiKey.startsWith("AQ.")) {
            return AiProvider.GEMINI;
        }
        return AiProvider.OPENAI;
    }

    public record UserAiKey(AiProvider provider, String apiKey) {
    }
}
