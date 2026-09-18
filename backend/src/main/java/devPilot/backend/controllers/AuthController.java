package devPilot.backend.controllers;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import devPilot.backend.dto.UserResponse;
import devPilot.backend.entity.User;
import devPilot.backend.security.AppUserPrincipal;
import devPilot.backend.security.CurrentUser;
import devPilot.backend.services.UserService;
import lombok.RequiredArgsConstructor;



@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final CurrentUser currentUser;
    private final UserService userService;

    @GetMapping("/login-url")
    public Map<String, String> loginUrl() {
        return Map.of("url", "/oauth2/authorization/github");
    }


    @GetMapping("/me")
    public ResponseEntity<UserResponse> me() {
        AppUserPrincipal principal = currentUser.require();
        // Fresh read: the session principal holds the login-time snapshot,
        // which predates key saves and provider switches.
        User user = userService.requiredById(principal.getId());
        boolean keySet = user.getOpenaiApiKey() != null && !user.getOpenaiApiKey().isBlank();
        String provider = user.getAiProvider() != null
                ? user.getAiProvider().toLowerCase()
                : "openai";
        return ResponseEntity.ok(new UserResponse(
                user.getId(),
                user.getGithubId(),
                user.getGithubUsername(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                keySet,
                provider));
    }
    
}
