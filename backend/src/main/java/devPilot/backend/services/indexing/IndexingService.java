package devPilot.backend.services.indexing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;

import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.Repository;
import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.services.UserService;
import devPilot.backend.services.ai.AiKeyResolver;
import devPilot.backend.services.ai.AiModelFactory;
import devPilot.backend.services.ai.RagSettings;
import devPilot.backend.services.github.GitHubRateLimiter;
import devPilot.backend.services.github.GithubApiClient;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {
    private static final int VECTOR_BATCH_SIZE = 32;
    private static final int PROGRESS_EVERY_N_FILES = 5;

    private final RepositoryRepository repositoryRepository;
    private final UserService userService;
    private final GithubApiClient gitHubApiClient;
    private final CodeFileFilter fileFilter;
    private final CodeChunker codeChunker;
    private final GitHubRateLimiter rateLimiter;
    private final AiKeyResolver aiKeyResolver;
    private final AiModelFactory aiModelFactory;

    private final ConcurrentHashMap<UUID, AtomicBoolean> pauseFlags = new ConcurrentHashMap<>();

    @Value("${app.indexing.max-file-bytes:102400}")
    private long maxFileBytes;

    public Repository startIndexing(UUID repoId, UUID userId) {
        aiKeyResolver.requireKey(userId);
        Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        if (repo.getIndexStatus() == IndexStatus.INDEXING) {
            throw new BadRequestException("Repository is already being indexed");
        }
        if (repo.getIndexStatus() == IndexStatus.PAUSED) {
            throw new BadRequestException("Indexing is paused — resume it to continue");
        }

        pauseFlags.put(repoId, new AtomicBoolean(false));
        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setFilesProcessed(0);
        repo.setFilesTotal(0);
        repo.setChunkCount(0);
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());
        return repositoryRepository.save(repo);
    }

    @Async("indexingExecutor")
     public void indexAsync(UUID repoId, UUID userId) {
        doIndexAsync(repoId, userId, false);
    }

    @Async("indexingExecutor")
    public void resumeAsync(UUID repoId, UUID userId) {
        doIndexAsync(repoId, userId, true);
    }

    private void doIndexAsync(UUID repoId, UUID userId, boolean resume) {
        try {
            doIndex(repoId, userId, resume);
        } catch (Exception ex) {
            if (isPaused(repoId)) {
                markPaused(repoId);
            } else {
                log.error("Indexing failed for repo {}", repoId, ex);
                markFailed(repoId, ex.getMessage());
            }
        } finally {
            pauseFlags.remove(repoId);
        }
    }

    public Repository pauseIndexing(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        if (repo.getIndexStatus() != IndexStatus.INDEXING) {
            throw new BadRequestException("Repository is not being indexed");
        }
        pauseFlags.computeIfAbsent(repoId, k -> new AtomicBoolean(false)).set(true);
        return repo;
    }

    public Repository resumeIndexing(UUID repoId, UUID userId) {
        aiKeyResolver.requireKey(userId);
        Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        if (repo.getIndexStatus() != IndexStatus.PAUSED) {
            throw new BadRequestException("Indexing is not paused");
        }
        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());
        pauseFlags.put(repoId, new AtomicBoolean(false));
        return repositoryRepository.save(repo);
    }

    private boolean isPaused(UUID repoId) {
        AtomicBoolean flag = pauseFlags.get(repoId);
        return flag != null && flag.get();
    }


      private void doIndex(UUID repoId, UUID userId, boolean resume) {
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        String token = userService.decryptAccessToken(userService.requiredById(userId));
        var userKey = aiKeyResolver.requireKey(userId);
        VectorStore userVectorStore = aiModelFactory.vectorStore(
                userId, userKey.provider(), userKey.apiKey());

        Map<String, Object> tree = gitHubApiClient.getRepoTree(
                token, repo.getOwner(), repo.getName(), repo.getDefaultBranch());
        List<String> filePaths = listIndexableFiles(tree);

        int startFrom = 0;
        int totalChunks = 0;
        if (resume) {
            // Continue where the pause left off; the file order below is stable,
            // so the first N files are exactly the ones already embedded.
            startFrom = Math.min(repo.getFilesProcessed(), filePaths.size());
            totalChunks = repo.getChunkCount();
        } else {
            deleteExistingVectors(userVectorStore, repoId.toString());
        }

        updateProgress(repoId, filePaths.size(), startFrom, totalChunks, IndexStatus.INDEXING, null);

        List<Document> batch = new ArrayList<>();
        int processed = startFrom;

        for (String path : filePaths.subList(startFrom, filePaths.size())) {
            if (isPaused(repoId)) {
                flushAndPause(repoId, userVectorStore, batch,
                        filePaths.size(), processed, totalChunks);
                return;
            }
            try {
                String content = gitHubApiClient.getFileContent(
                        token, repo.getOwner(), repo.getName(), path);
                List<Document> chunks = codeChunker.chunkFile(repoId.toString(), path, content);
                batch.addAll(chunks);
                totalChunks += chunks.size();
                if (batch.size() >= VECTOR_BATCH_SIZE) {
                    if (isPaused(repoId)) {
                        flushAndPause(repoId, userVectorStore, batch,
                                filePaths.size(), processed, totalChunks);
                        return;
                    }
                    userVectorStore.add(batch);
                    batch.clear();
                }
            } catch (Exception ex) {
                log.warn("Skipping file {} in {}: {}", path, repo.getFullName(), ex.getMessage());
            }

            processed++;
            if (processed % PROGRESS_EVERY_N_FILES == 0 || processed == filePaths.size()) {
                updateProgress(repoId, filePaths.size(), processed, totalChunks, IndexStatus.INDEXING, null);
            }
            rateLimiter.pause();
        }

        if (!batch.isEmpty()) {
            userVectorStore.add(batch);
        }

        markReady(repoId, filePaths.size(), processed, totalChunks, repo.getFullName());
    }

    /** Persist any buffered vectors, then park the job as PAUSED for later resume. */
    private void flushAndPause(UUID repoId, VectorStore store, List<Document> batch,
            int total, int processed, int chunks) {
        if (!batch.isEmpty()) {
            try {
                store.add(batch);
            } catch (Exception ex) {
                log.warn("Could not flush final batch for paused repo {}: {}", repoId, ex.getMessage());
            }
            batch.clear();
        }
        updateProgress(repoId, total, processed, chunks, IndexStatus.PAUSED, null);
        log.info("Indexing paused for repo {} at {}/{} files", repoId, processed, total);
    }


       @SuppressWarnings("unchecked")
    private List<String> listIndexableFiles(Map<String, Object> tree) {
        if (tree == null || tree.get("tree") == null) {
            return List.of();
        }

        List<Map<String, Object>> entries = (List<Map<String, Object>>) tree.get("tree");
        return entries.stream()
                .filter(entry -> "blob".equals(String.valueOf(entry.get("type"))))
                .filter(entry -> {
                    String path = String.valueOf(entry.get("path"));
                    long size = entry.get("size") instanceof Number n ? n.longValue() : 0L;
                    return fileFilter.isEligible(path, size, maxFileBytes);
                })
                .map(entry -> String.valueOf(entry.get("path")))
                .sorted()
                .toList();
    }

     private void deleteExistingVectors(VectorStore store, String repoId) {
         try {
             var filter = new FilterExpressionBuilder().eq(RagSettings.METADATA_REPO_ID, repoId).build();
             store.delete(filter);
         } catch (Exception ex) {
             log.warn("Could not delete existing vectors for repo {}: {}", repoId, ex.getMessage());
         }
     };

      @Transactional
    protected void updateProgress(
            UUID repoId,
            int total,
            int processed,
            int chunks,
            IndexStatus status,
            String error) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setFilesTotal(total);
            repo.setFilesProcessed(processed);
            repo.setChunkCount(chunks);
            repo.setIndexStatus(status);
            repo.setErrorMessage(error);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }

      @Transactional
    protected void markReady(UUID repoId, int totalFiles, int processedFiles, int totalChunks, String fullName) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.READY);
            repo.setFilesTotal(totalFiles);
            repo.setFilesProcessed(processedFiles);
            repo.setChunkCount(totalChunks);
            repo.setIndexedAt(Instant.now());
            repo.setErrorMessage(null);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
        log.info("Indexed {} files ({} chunks) for {}", processedFiles, totalChunks, fullName);
    }

     @Transactional
    protected void markPaused(UUID repoId) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.PAUSED);
            repo.setErrorMessage(null);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
        log.info("Indexing paused for repo {}", repoId);
    }

      @Transactional
    protected void markFailed(UUID repoId, String message) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.FAILED);
            repo.setErrorMessage(message != null && message.length() > 2000
                    ? message.substring(0, 2000)
                    : message);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }

}
