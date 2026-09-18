package devPilot.backend.services.ai;

import java.util.UUID;

import org.springframework.stereotype.Component;

import devPilot.backend.entity.User;
import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.services.UserService;
import lombok.RequiredArgsConstructor;

/**
 * Resolves the current user's provider + decrypted API key, failing fast with a
 * message the frontend shows as "add your key in Settings".
 */
@Component
@RequiredArgsConstructor
public class AiKeyResolver {

    public static final String MISSING_KEY_MESSAGE =
            "Add your AI API key in Settings to use AI features";

    private final UserService userService;

    public UserAiKey requireKey(UUID userId) {
        User user = userService.requiredById(userId);
        if (!userService.hasAiKey(user)) {
            throw new BadRequestException(MISSING_KEY_MESSAGE);
        }
        return new UserAiKey(userService.providerOf(user), userService.decryptAiKey(user));
    }

    public record UserAiKey(AiProvider provider, String apiKey) {
    }
}
