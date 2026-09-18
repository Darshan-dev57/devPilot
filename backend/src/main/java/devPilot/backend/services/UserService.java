package devPilot.backend.services;

import java.util.Map;
import java.util.UUID;

import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import devPilot.backend.entity.User;
import devPilot.backend.repository.UserRepository;

import devPilot.backend.services.ai.AiModelFactory;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {
    public final UserRepository userRepository;
    public final TextEncryptor tokenEncryptor;
    private final AiModelFactory aiModelFactory;
    
   @Transactional
    public User upsertFromGitHub(Map<String, Object> attributes, String accessToken, String scopes) {
        Long githubId = toLong(attributes.get("id"));
        String login = String.valueOf(attributes.get("login"));
        String name = attributes.get("name") != null
                ? String.valueOf(attributes.get("name"))
                : login;
        String avatarUrl = attributes.get("avatar_url") != null
                ? String.valueOf(attributes.get("avatar_url"))
                : null;

        String encryptedToken = tokenEncryptor.encrypt(accessToken);

        User user = userRepository.findByGithubId(githubId).orElseGet(User::new);
        user.setGithubId(githubId);
        user.setGithubUsername(login);
        user.setDisplayName(name);
        user.setAvatarUrl(avatarUrl);
        user.setAccessToken(encryptedToken);
        user.setTokenScopes(scopes);
        return userRepository.save(user);
    }
    @Transactional(readOnly = true)
    public User requiredById(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    public String decryptAccessToken(User user) {
        return tokenEncryptor.decrypt(user.getAccessToken());
    }

    @Transactional
    public void saveOpenAiKey(UUID id, String rawKey) {
        User user = requiredById(id);
        user.setOpenaiApiKey(tokenEncryptor.encrypt(rawKey));
        user.setOpenaiKeyUpdatedAt(java.time.Instant.now());
        userRepository.save(user);
        aiModelFactory.evict(id);
    }

    @Transactional
    public void removeOpenAiKey(UUID id) {
        User user = requiredById(id);
        user.setOpenaiApiKey(null);
        user.setOpenaiKeyUpdatedAt(null);
        userRepository.save(user);
        aiModelFactory.evict(id);
    }

    @Transactional(readOnly = true)
    public boolean hasOpenAiKey(User user) {
        return user.getOpenaiApiKey() != null && !user.getOpenaiApiKey().isBlank();
    }

    public String decryptOpenAiKey(User user) {
        return tokenEncryptor.decrypt(user.getOpenaiApiKey());
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

   

}
