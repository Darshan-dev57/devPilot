package devPilot.backend.services;

import java.util.Map;
import java.util.UUID;

import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.User;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.repository.UserRepository;

import devPilot.backend.services.ai.AiModelFactory;
import devPilot.backend.services.ai.AiProvider;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {
    public final UserRepository userRepository;
    public final TextEncryptor tokenEncryptor;
    private final AiModelFactory aiModelFactory;
    private final RepositoryRepository repositoryRepository;
    
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
    public void saveAiKey(UUID id, AiProvider provider, String rawKey) {
        validateKeyFormat(provider, rawKey);
        User user = requiredById(id);
        AiProvider previous = providerOf(user);
        user.setOpenaiApiKey(tokenEncryptor.encrypt(rawKey));
        user.setOpenaiKeyUpdatedAt(java.time.Instant.now());
        user.setAiProvider(provider.name());
        userRepository.save(user);
        aiModelFactory.evict(id);
        if (previous != provider) {
            // Vectors live in per-provider tables: old indexes no longer apply.
            resetIndexState(id);
        }
    }

    @Transactional
    public void removeAiKey(UUID id) {
        User user = requiredById(id);
        user.setOpenaiApiKey(null);
        user.setOpenaiKeyUpdatedAt(null);
        userRepository.save(user);
        aiModelFactory.evict(id);
    }

    @Transactional(readOnly = true)
    public boolean hasAiKey(User user) {
        return user.getOpenaiApiKey() != null && !user.getOpenaiApiKey().isBlank();
    }

    @Transactional(readOnly = true)
    public AiProvider providerOf(User user) {
        return AiProvider.parse(user.getAiProvider());
    }

    public String decryptAiKey(User user) {
        return tokenEncryptor.decrypt(user.getOpenaiApiKey());
    }

    private void validateKeyFormat(AiProvider provider, String rawKey) {
        if (rawKey == null || rawKey.isBlank() || rawKey.length() > 1000) {
            throw new devPilot.backend.exceptions.BadRequestException("API key must not be blank");
        }
        if (provider == AiProvider.OPENAI && !rawKey.startsWith("sk-")) {
            throw new devPilot.backend.exceptions.BadRequestException("OpenAI API key must start with sk-");
        }
        if (provider == AiProvider.GEMINI && rawKey.trim().length() < 10) {
            throw new devPilot.backend.exceptions.BadRequestException("That Gemini API key looks too short");
        }
    }

    private void resetIndexState(UUID userId) {
        repositoryRepository.findAll().stream()
                .filter(repo -> repo.getUserId().equals(userId))
                .forEach(repo -> {
                    repo.setIndexStatus(IndexStatus.PENDING);
                    repo.setFilesTotal(0);
                    repo.setFilesProcessed(0);
                    repo.setChunkCount(0);
                    repo.setErrorMessage(null);
                    repo.setUpdatedAt(java.time.Instant.now());
                    repositoryRepository.save(repo);
                });
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

   

}
