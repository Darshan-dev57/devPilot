package devPilot.backend.controllers;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import devPilot.backend.dto.SaveOpenAiKeyRequest;
import devPilot.backend.security.CurrentUser;
import devPilot.backend.services.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Lets each user manage their own OpenAI API key (bring-your-own-key).
 * Keys are stored encrypted and only ever used for that user's AI requests.
 */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserSettingsController {

    private final CurrentUser currentUser;
    private final UserService userService;

    @PutMapping("/openai-key")
    public ResponseEntity<Map<String, Object>> saveOpenAiKey(
            @Valid @RequestBody SaveOpenAiKeyRequest request) {
        UUID userId = currentUser.require().getId();
        userService.saveOpenAiKey(userId, request.apiKey().trim());
        return ResponseEntity.ok(Map.of("openaiKeySet", true));
    }

    @DeleteMapping("/openai-key")
    public ResponseEntity<Map<String, Object>> deleteOpenAiKey() {
        UUID userId = currentUser.require().getId();
        userService.removeOpenAiKey(userId);
        return ResponseEntity.ok(Map.of("openaiKeySet", false));
    }
}
