package devPilot.backend.services.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import devPilot.backend.exceptions.ServiceUnavailableException;

/**
 * The one AI key this server uses, set by the operator in an environment variable.
 * Visitors never paste a key.
 *
 * <p>GEMINI_API_KEY is the default path (Google's Gemini API has a free tier).
 * OPENAI_API_KEY works too. If both are set, app.ai.provider picks one.
 */
@Component
public class AiKeyResolver {

    public static final String NOT_CONFIGURED =
            "This DevPilot server has no AI key configured yet. The site owner needs to set GEMINI_API_KEY (or OPENAI_API_KEY, or AI_PROVIDER=ollama).";

    private final String geminiKey;
    private final String openaiKey;
    private final String preferred;

    public AiKeyResolver(
            @Value("${app.ai.gemini.api-key:}") String geminiKey,
            @Value("${app.ai.openai.api-key:}") String openaiKey,
            @Value("${app.ai.provider:}") String preferred) {
        this.geminiKey = geminiKey == null ? "" : geminiKey.trim();
        this.openaiKey = openaiKey == null ? "" : openaiKey.trim();
        this.preferred = preferred == null ? "" : preferred.trim();
    }

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

    /** The provider in use: the explicit setting, else whichever key is present (Gemini first). */
    public AiProvider provider() {
        if (!preferred.isEmpty()) {
            return AiProvider.parse(preferred);
        }
        if (!geminiKey.isEmpty()) {
            return AiProvider.GEMINI;
        }
        return openaiKey.isEmpty() ? AiProvider.GEMINI : AiProvider.OPENAI;
    }

    public boolean configured() {
        if (provider() == AiProvider.OLLAMA) {
            return true;
        }
        return !(provider() == AiProvider.GEMINI ? geminiKey : openaiKey).isEmpty();
    }

    public record UserAiKey(AiProvider provider, String apiKey) {
    }
}
