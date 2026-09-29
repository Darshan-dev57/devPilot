package devPilot.backend.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;

import devPilot.backend.dto.IndexStatusResponse;
import devPilot.backend.dto.RepositoryResponse;
import devPilot.backend.entity.Repository;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.services.github.GithubApiClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RepoService {
    private final RepositoryRepository repositoryRepository;
    private final GithubApiClient gitHubApiClient;

    @Transactional
    public List<RepositoryResponse> syncAndListRepos() {
        List<Map<String, Object>> remoteRepos = gitHubApiClient.listUserRepos();

        List<Repository> saved = new ArrayList<>();
        Set<UUID> syncedIds = new HashSet<>();

        for (Map<String, Object> remote : remoteRepos) {
            Long githubRepoId = toLong(remote.get("id"));
            Repository repo = repositoryRepository
                    .findByGithubRepoId(githubRepoId)
                    .orElseGet(Repository::new);

            fromRemote(repo, remote);
            repo = repositoryRepository.save(repo);
            saved.add(repo);
            syncedIds.add(repo.getId());
        }

        for (Repository stored : repositoryRepository.findAll()) {
            if (!syncedIds.contains(stored.getId())) {
                saved.add(stored);
            }
        }

        return saved.stream()
                .sorted((a, b) -> a.getFullName().compareToIgnoreCase(b.getFullName()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public Repository addPublicRepo(String owner, String name) {
        Map<String, Object> remote;
        try {
            remote = gitHubApiClient.getRepository(owner, name);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new NotFoundException("Repository not found or not accessible");
            }
            throw ex;
        }
        if (remote == null || remote.get("id") == null) {
            throw new NotFoundException("Repository not found or not accessible");
        }
        Long githubRepoId = toLong(remote.get("id"));
        return repositoryRepository.findByGithubRepoId(githubRepoId)
                .orElseGet(() -> {
                    Repository repo = new Repository();
                    fromRemote(repo, remote);
                    return repositoryRepository.save(repo);
                });
    }

    @Transactional(readOnly = true)
    public List<RepositoryResponse> listStored() {
        return repositoryRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Repository requireOwned(UUID repoId) {
        return repositoryRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
    }

    @Transactional(readOnly = true)
    public IndexStatusResponse status(UUID repoId) {
        Repository repo = requireOwned(repoId);
        return new IndexStatusResponse(
                repo.getId(),
                repo.getIndexStatus(),
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getChunkCount(),
                repo.getIndexedAt(),
                repo.getErrorMessage());
    }

    public RepositoryResponse toResponse(Repository repo) {
        return new RepositoryResponse(
                repo.getId(),
                repo.getGithubRepoId(),
                repo.getOwner(),
                repo.getName(),
                repo.getFullName(),
                repo.isPrivate(),
                repo.getDefaultBranch(),
                repo.getLanguage(),
                repo.getHtmlUrl(),
                repo.getDescription(),
                repo.getIndexStatus(),
                repo.getIndexedAt(),
                repo.getChunkCount(),
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getErrorMessage());
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static void fromRemote(Repository repo, Map<String, Object> remote) {
        Long githubRepoId = toLong(remote.get("id"));
        String fullName = String.valueOf(remote.get("full_name"));
        String[] parts = fullName.split("/", 2);

        repo.setGithubRepoId(githubRepoId);
        repo.setOwner(parts.length > 0 ? parts[0] : String.valueOf(remote.get("owner")));
        repo.setName(parts.length > 1 ? parts[1] : String.valueOf(remote.get("name")));
        repo.setFullName(fullName);
        repo.setPrivate(Boolean.TRUE.equals(remote.get("private")));
        repo.setDefaultBranch(remote.get("default_branch") != null
                ? String.valueOf(remote.get("default_branch"))
                : "main");
        repo.setLanguage(remote.get("language") != null ? String.valueOf(remote.get("language")) : null);
        repo.setHtmlUrl(remote.get("html_url") != null ? String.valueOf(remote.get("html_url")) : null);
        repo.setDescription(remote.get("description") != null ? String.valueOf(remote.get("description")) : null);
        repo.setUpdatedAt(Instant.now());
        if (repo.getOwner() == null || repo.getOwner().isBlank()) {
            Object ownerObj = remote.get("owner");
            if (ownerObj instanceof Map<?, ?> ownerMap && ownerMap.get("login") != null) {
                repo.setOwner(String.valueOf(ownerMap.get("login")));
            }
        }
    }
}
