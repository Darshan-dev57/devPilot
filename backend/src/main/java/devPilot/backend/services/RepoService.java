package devPilot.backend.services;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import devPilot.backend.dto.IndexStatusResponse;
import devPilot.backend.dto.RepositoryResponse;
import devPilot.backend.entity.ChatSession;
import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.Repository;
import devPilot.backend.entity.VisitorRepository;
import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.repository.ChatMessageRepository;
import devPilot.backend.repository.ChatSessionRepository;
import devPilot.backend.repository.VisitorRepositoryRepository;
import devPilot.backend.services.ai.AiProvider;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.services.github.GitHubException;
import devPilot.backend.services.github.GithubApiClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RepoService {
    private final RepositoryRepository repositoryRepository;
    private final VisitorRepositoryRepository visitorRepos;
    private final ChatSessionRepository chatSessions;
    private final ChatMessageRepository chatMessages;
    private final JdbcTemplate jdbcTemplate;
    private final GithubApiClient gitHubApiClient;

    /** GitHub reports a repo's size in KB. Big repos would burn the free AI quota. */
    @Value("${app.limits.max-repo-mb:60}")
    private long maxRepoMb;

    /**
     * Adds a repository for chat, or returns the existing row if it is already stored.
     *
     * <p>Because anyone can paste any public URL, repositories are global. An already-indexed
     * repository is returned as-is rather than re-indexed: re-running indexing would spend
     * the server's GitHub API quota and the visitor's embedding credits to reproduce vectors
     * that already exist.
     */
    @Transactional
    public Repository addPublicRepo(String owner, String name, UUID visitorId) {
        Repository repo = findOrCreate(owner, name);
        if (!visitorRepos.existsByVisitorIdAndRepositoryId(visitorId, repo.getId())) {
            visitorRepos.save(VisitorRepository.builder().visitorId(visitorId).repositoryId(repo.getId()).build());
        }
        return repo;
    }

    private Repository findOrCreate(String owner, String name) {
        Map<String, Object> remote = gitHubApiClient.getRepository(owner, name);
        if (remote == null || remote.get("id") == null) {
            throw new GitHubException(GitHubException.Kind.NOT_FOUND, "Repository not found.");
        }
        // The server token may be able to read the operator's own private repos. Anyone can
        // paste a URL here, so private repos must never be indexed or exposed.
        if (Boolean.TRUE.equals(remote.get("private"))) {
            throw new GitHubException(GitHubException.Kind.PRIVATE_REPO,
                    "That repository is private. DevPilot only works with public repos.");
        }
        Long sizeKb = remote.get("size") instanceof Number n ? n.longValue() : null;
        if (maxRepoMb > 0 && sizeKb != null && sizeKb > maxRepoMb * 1024) {
            throw new BadRequestException("That repo is about " + (sizeKb / 1024) + " MB. The free server takes repos up to "
                    + maxRepoMb + " MB.");
        }
        Long githubRepoId = toLong(remote.get("id"));
        return repositoryRepository.findByGithubRepoId(githubRepoId)
                .orElseGet(() -> {
                    Repository repo = new Repository();
                    fromRemote(repo, remote);
                    return repositoryRepository.save(repo);
                });
    }

    /**
     * The repositories this visitor added, newest first. Nobody sees anyone else's list.
     * There is no sync-from-GitHub path: repos only enter through an explicit add-by-URL.
     */
    @Transactional(readOnly = true)
    public List<RepositoryResponse> listFor(UUID visitorId) {
        List<UUID> ids = visitorRepos.findByVisitorIdOrderByCreatedAtDesc(visitorId).stream()
                .map(VisitorRepository::getRepositoryId)
                .toList();
        Map<UUID, Repository> byId = new HashMap<>();
        repositoryRepository.findAllById(ids).forEach(r -> byId.put(r.getId(), r));
        return ids.stream().map(byId::get).filter(Objects::nonNull).map(this::toResponse).toList();
    }

    /** The repository, if this visitor has it in their list; 404 otherwise. */
    @Transactional(readOnly = true)
    public Repository requireVisible(UUID repoId, UUID visitorId) {
        if (!visitorRepos.existsByVisitorIdAndRepositoryId(visitorId, repoId)) {
            throw new NotFoundException("Repository not found");
        }
        return repositoryRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
    }

    /**
     * Removes the repo from this visitor's list along with their chats. When nobody else
     * has it, the repository row and its vectors are deleted too.
     */
    @Transactional
    public void remove(UUID repoId, UUID visitorId) {
        Repository repo = requireVisible(repoId, visitorId);
        boolean lastUser = visitorRepos.countByRepositoryId(repoId) <= 1;
        if (lastUser && repo.getIndexStatus() == IndexStatus.INDEXING) {
            throw new BadRequestException("This repo is being indexed. Pause it first, then remove it.");
        }
        deleteChats(chatSessions.findByRepositoryIdAndVisitorId(repoId, visitorId));
        visitorRepos.deleteByVisitorIdAndRepositoryId(visitorId, repoId);
        if (lastUser) {
            deleteChats(chatSessions.findByRepositoryId(repoId));
            for (AiProvider provider : AiProvider.values()) {
                String table = provider.tableName();
                Boolean exists = jdbcTemplate.queryForObject("select to_regclass(?) is not null", Boolean.class, "public." + table);
                if (Boolean.TRUE.equals(exists)) {
                    jdbcTemplate.update("delete from public." + table + " where metadata->>'repoId' = ?", repoId.toString());
                }
            }
            repositoryRepository.delete(repo);
        }
    }

    private void deleteChats(List<ChatSession> sessions) {
        if (sessions.isEmpty()) {
            return;
        }
        chatMessages.deleteBySessionIdIn(sessions.stream().map(ChatSession::getId).toList());
        chatSessions.deleteAll(sessions);
    }

    @Transactional(readOnly = true)
    public IndexStatusResponse status(UUID repoId, UUID visitorId) {
        Repository repo = requireVisible(repoId, visitorId);
        return new IndexStatusResponse(
                repo.getId(),
                repo.getIndexStatus(),
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getChunkCount(),
                repo.getIndexedAt(),
                repo.getErrorMessage(),
                repo.getIndexPhase(),
                repo.getIndexedSha(),
                repo.getLastIndexMode(),
                repo.getLastIndexDurationMs());
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
                repo.getErrorMessage(),
                repo.getIndexedSha(),
                repo.getEmbeddingProvider(),
                repo.getLastIndexMode(),
                repo.getLastIndexDurationMs(),
                repo.getIndexPhase());
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
