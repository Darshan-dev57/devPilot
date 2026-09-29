package devPilot.backend.services.ai;

import org.springframework.stereotype.Component;

import devPilot.backend.exceptions.BadRequestException;

@Component
public class AiKeyResolver {

    public static final String MISSING_KEY_MESSAGE =
            "Add your AI API key in Settings to use AI features";

    public UserAiKey requireKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BadRequestException(MISSING_KEY_MESSAGE);
        }
        AiProvider provider = apiKey.startsWith("AIza") ? AiProvider.GEMINI : AiProvider.OPENAI;
        return new UserAiKey(provider, apiKey);
    }

    public record UserAiKey(AiProvider provider, String apiKey) {
    }
}
