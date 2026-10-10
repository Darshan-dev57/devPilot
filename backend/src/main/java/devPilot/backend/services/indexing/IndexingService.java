package devPilot.backend.services.indexing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.Repository;
import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.services.ai.AiErrors;
import devPilot.backend.services.ai.AiKeyResolver;
import devPilot.backend.services.ai.AiKeyResolver.UserAiKey;
import devPilot.backend.services.ai.AiModelFactory;
import devPilot.backend.services.ai.RagSettings;
import devPilot.backend.services.ai.RetryingEmbeddingModel;
import devPilot.backend.services.github.GitHubException;
import devPilot.backend.services.github.GithubApiClient;
import devPilot.backend.services.github.GithubApiClient.FileChange;
import devPilot.backend.services.github.GithubApiClient.SourceFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Indexes a repository into pgvector.
 *
 * <p>The first run downloads the repository as one zip, chunks every eligible file and
 * embeds the chunks. Later runs are incremental: GitHub's compare API says which files
 * changed since the indexed commit, and only those files are deleted and re-embedded.
 * If nothing changed, nothing is embedded at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {

    public static final String MODE_FULL = "FULL";
    public static final String MODE_INCREMENTAL = "INCREMENTAL";
    public static final String MODE_UP_TO_DATE = "UP_TO_DATE";

    private static final int VECTOR_BATCH_SIZE = 64;

    private final RepositoryRepository repositoryRepository;
    private final GithubApiClient gitHubApiClient;
    private final CodeFileFilter fileFilter;
    private final CodeChunker codeChunker;
    private final AiKeyResolver aiKeyResolver;
    private final AiModelFactory aiModelFactory;
    private final JdbcTemplate jdbcTemplate;

    private final ConcurrentHashMap<UUID, AtomicBoolean> pauseFlags = new ConcurrentHashMap<>();

    @Value("${app.indexing.max-file-bytes:102400}")
    private long maxFileBytes;

    @Value("${app.limits.max-files:800}")
    private int maxFiles;

    public Repository startIndexing(UUID repoId) {
        UserAiKey key = aiKeyResolver.requireKey();
        Repository repo = require(repoId);
        if (repo.getIndexStatus() == IndexStatus.PAUSED) {
            throw new BadRequestException("Indexing is paused. Resume it to continue");
        }
        aiModelFactory.verifyKey(key.provider(), key.apiKey());
        pauseFlags.put(repoId, new AtomicBoolean(false));
        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setIndexPhase("Queued");
        repo.setFilesProcessed(0);
        repo.setFilesTotal(0);
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());
        return repositoryRepository.save(repo);
    }

    @Async("indexingExecutor")
    public void indexAsync(UUID repoId) {
        run(repoId, false);
    }

    @Async("indexingExecutor")
    public void resumeAsync(UUID repoId) {
        run(repoId, true);
    }

    public Repository pauseIndexing(UUID repoId) {
        Repository repo = require(repoId);
        if (repo.getIndexStatus() != IndexStatus.INDEXING) {
            throw new BadRequestException("Repository is not being indexed");
        }
        pauseFlags.computeIfAbsent(repoId, k -> new AtomicBoolean(false)).set(true);
        return repo;
    }

    public Repository resumeIndexing(UUID repoId) {
        UserAiKey key = aiKeyResolver.requireKey();
        Repository repo = require(repoId);
        if (repo.getIndexStatus() != IndexStatus.PAUSED) {
            throw new BadRequestException("Indexing is not paused");
        }
        aiModelFactory.verifyKey(key.provider(), key.apiKey());
        pauseFlags.put(repoId, new AtomicBoolean(false));
        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setIndexPhase("Resuming");
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());
        return repositoryRepository.save(repo);
    }

    /** Runs one index job synchronously. Public so tests can drive it without @Async. */
    public void run(UUID repoId, boolean resume) {
        UserAiKey key = aiKeyResolver.requireKey();
        long started = System.nanoTime();
        RetryingEmbeddingModel.WAIT_LISTENER.set(message -> phase(repoId, message));
        try {
            String mode = index(repoId, key, resume);
            if (mode != null) {
                long millis = (System.nanoTime() - started) / 1_000_000;
                markReady(repoId, key, mode, millis);
            }
        } catch (GitHubException | BadRequestException ex) {
            log.warn("Indexing {} failed: {}", repoId, ex.getMessage());
            markFailed(repoId, ex.getMessage());
        } catch (Exception ex) {
            if (isPaused(repoId)) {
                update(repoId, r -> {
                    r.setIndexStatus(IndexStatus.PAUSED);
                    r.setIndexPhase("Paused");
                });
            } else {
                log.error("Indexing failed for repo {}", repoId, ex);
                markFailed(repoId, AiErrors.describe(ex, key.provider()));
            }
        } finally {
            RetryingEmbeddingModel.WAIT_LISTENER.remove();
            pauseFlags.remove(repoId);
        }
    }

    /** Returns the mode that finished, or null when the job was paused. */
    private String index(UUID repoId, UserAiKey key, boolean resume) {
        Repository repo = require(repoId);
        VectorStore store = aiModelFactory.vectorStore(key.provider(), key.apiKey());
        String providerName = key.provider().name();

        phase(repoId, "Checking latest commit");
        String head = resume && repo.getPendingSha() != null
                ? repo.getPendingSha()
                : gitHubApiClient.getHeadSha(repo.getOwner(), repo.getName(), repo.getDefaultBranch());

        boolean sameProvider = providerName.equals(repo.getEmbeddingProvider());
        // A full run clears indexedSha first, so a non-null value means a complete earlier
        // index exists (a paused incremental run is simply redone; it is idempotent).
        boolean hasIndex = repo.getIndexedSha() != null && sameProvider;

        if (hasIndex && head.equals(repo.getIndexedSha())) {
            return MODE_UP_TO_DATE;
        }
        if (hasIndex) {
            phase(repoId, "Finding changed files");
            List<FileChange> changes = gitHubApiClient.compare(
                    repo.getOwner(), repo.getName(), repo.getIndexedSha(), head);
            if (changes != null) {
                update(repoId, r -> r.setPendingSha(head));
                return indexChanges(repo, store, head, changes) ? MODE_INCREMENTAL : null;
            }
            log.info("Compare unavailable for {}, falling back to a full index", repo.getFullName());
        }
        return indexEverything(repo, store, head, providerName, resume) ? MODE_FULL : null;
    }

    private boolean indexEverything(Repository repo, VectorStore store, String head, String providerName, boolean resume) {
        UUID repoId = repo.getId();
        int startFrom = 0;
        if (resume) {
            startFrom = repo.getFilesProcessed();
        } else {
            phase(repoId, "Clearing old vectors");
            deleteVectors(store, repoId, null);
            update(repoId, r -> {
                r.setIndexedSha(null);
                r.setPendingSha(head);
                r.setEmbeddingProvider(providerName);
                r.setChunkCount(0);
            });
        }

        phase(repoId, "Downloading repository");
        List<SourceFile> files = gitHubApiClient.downloadSnapshot(
                repo.getOwner(), repo.getName(), head, this::eligible, maxFileBytes);
        files = files.stream().sorted((a, b) -> a.path().compareTo(b.path())).toList();
        if (maxFiles > 0 && files.size() > maxFiles) {
            throw new BadRequestException("This repo has " + files.size() + " code files. The free server indexes up to "
                    + maxFiles + ", so try a smaller repo.");
        }
        return embedFiles(repoId, store, files, Math.min(startFrom, files.size()));
    }

    private boolean indexChanges(Repository repo, VectorStore store, String head, List<FileChange> changes) {
        UUID repoId = repo.getId();
        Set<String> toDelete = new HashSet<>();
        Set<String> toEmbed = new HashSet<>();
        for (FileChange change : changes) {
            toDelete.add(change.path());
            if (change.previousPath() != null) {
                toDelete.add(change.previousPath());
            }
            if (!change.removed() && eligible(change.path())) {
                toEmbed.add(change.path());
            }
        }

        phase(repoId, "Removing vectors for " + toDelete.size() + " changed file(s)");
        for (String path : toDelete) {
            deleteVectors(store, repoId, path);
        }
        if (toEmbed.isEmpty()) {
            update(repoId, r -> {
                r.setFilesTotal(0);
                r.setFilesProcessed(0);
            });
            return true;
        }
        phase(repoId, "Downloading changed files");
        List<SourceFile> files = gitHubApiClient.downloadSnapshot(
                repo.getOwner(), repo.getName(), head, toEmbed::contains, maxFileBytes);
        return embedFiles(repoId, store, files, 0);
    }

    /** Chunks and embeds {@code files[startFrom..]}; false if the job was paused. */
    private boolean embedFiles(UUID repoId, VectorStore store, List<SourceFile> files, int startFrom) {
        int total = files.size();
        update(repoId, r -> {
            r.setFilesTotal(total);
            r.setFilesProcessed(startFrom);
            r.setIndexPhase("Embedding code");
        });
        List<Document> batch = new ArrayList<>();
        int processed = startFrom;
        for (SourceFile file : files.subList(startFrom, total)) {
            if (isPaused(repoId)) {
                flush(store, batch);
                int done = processed;
                update(repoId, r -> {
                    r.setFilesProcessed(done);
                    r.setIndexStatus(IndexStatus.PAUSED);
                    r.setIndexPhase("Paused");
                });
                log.info("Indexing paused for repo {} at {}/{} files", repoId, done, total);
                return false;
            }
            batch.addAll(codeChunker.chunkFile(repoId.toString(), file.path(), file.content()));
            processed++;
            if (batch.size() >= VECTOR_BATCH_SIZE) {
                flush(store, batch);
                int done = processed;
                update(repoId, r -> {
                    r.setFilesProcessed(done);
                    r.setIndexPhase("Embedding code");
                });
            }
        }
        flush(store, batch);
        int done = processed;
        update(repoId, r -> r.setFilesProcessed(done));
        return true;
    }

    /** Embedding errors propagate: a bad key must fail the job, not mark it READY with no vectors. */
    private static void flush(VectorStore store, List<Document> batch) {
        if (!batch.isEmpty()) {
            store.add(List.copyOf(batch));
            batch.clear();
        }
    }

    boolean eligible(String path) {
        // Size is checked again on the downloaded bytes; the zip has no reliable size up front.
        return fileFilter.isEligible(path, 0, maxFileBytes);
    }

    private void deleteVectors(VectorStore store, UUID repoId, String filePath) {
        var b = new FilterExpressionBuilder();
        var filter = filePath == null
                ? b.eq(RagSettings.METADATA_REPO_ID, repoId.toString()).build()
                : b.and(b.eq(RagSettings.METADATA_REPO_ID, repoId.toString()),
                        b.eq(RagSettings.METADATA_FILE_PATH, filePath)).build();
        store.delete(filter);
    }

    private int countVectors(UserAiKey key, UUID repoId) {
        String table = aiModelFactory.tableName(key.provider());
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from public." + table + " where metadata->>'repoId' = ?",
                Integer.class, repoId.toString());
        return count == null ? 0 : count;
    }

    private boolean isPaused(UUID repoId) {
        AtomicBoolean flag = pauseFlags.get(repoId);
        return flag != null && flag.get();
    }

    private Repository require(UUID repoId) {
        return repositoryRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
    }

    private void phase(UUID repoId, String phase) {
        update(repoId, r -> r.setIndexPhase(phase));
    }

    private void update(UUID repoId, Consumer<Repository> change) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            change.accept(repo);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }

    private void markReady(UUID repoId, UserAiKey key, String mode, long millis) {
        int chunks = countVectors(key, repoId);
        update(repoId, repo -> {
            if (repo.getPendingSha() != null) {
                repo.setIndexedSha(repo.getPendingSha());
            }
            repo.setPendingSha(null);
            repo.setIndexStatus(IndexStatus.READY);
            repo.setIndexPhase(null);
            repo.setEmbeddingProvider(key.provider().name());
            repo.setChunkCount(chunks);
            repo.setLastIndexMode(mode);
            repo.setLastIndexDurationMs(millis);
            repo.setIndexedAt(Instant.now());
            repo.setErrorMessage(null);
            log.info("Indexed {} ({}): {} chunks in {} ms", repo.getFullName(), mode, chunks, millis);
        });
    }

    private void markFailed(UUID repoId, String message) {
        update(repoId, repo -> {
            repo.setIndexStatus(IndexStatus.FAILED);
            repo.setIndexPhase(null);
            repo.setErrorMessage(message);
        });
    }
}
