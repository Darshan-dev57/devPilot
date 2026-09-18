package devPilot.backend.controllers;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import devPilot.backend.dto.SaveAiKeyRequest;
import devPilot.backend.security.CurrentUser;
import devPilot.backend.services.UserService;
import devPilot.backend.services.ai.AiProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Lets each user pick an AI provider (OpenAI or Gemini) and manage their own
 * API key (bring-your-own-key). Keys are stored encrypted and only ever used
 * for that user's AI requests.
 */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserSettingsController {

    private final CurrentUser currentUser;
    private final UserService userService;

    @PutMapping("/ai-key")
    public ResponseEntity<Map<String, Object>> saveAiKey(
            @Valid @RequestBody SaveAiKeyRequest request) {
        UUID userId = currentUser.require().getId();
        AiProvider provider = AiProvider.parse(request.provider());
        userService.saveAiKey(userId, provider, request.apiKey().trim());
        return ResponseEntity.ok(Map.of(
                "aiKeySet", true,
                "aiProvider", provider.name().toLowerCase()));
    }

    @DeleteMapping("/ai-key")
    public ResponseEntity<Map<String, Object>> deleteAiKey() {
        UUID userId = currentUser.require().getId();
        userService.removeAiKey(userId);
        return ResponseEntity.ok(Map.of("aiKeySet", false));
    }
}
