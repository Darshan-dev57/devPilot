package devPilot.backend.services.indexing;

import java.time.Instant;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.Repository;
import devPilot.backend.repository.RepositoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A container restart (deploy, crash, scale event) kills in-flight indexing loops
 * while their rows stay INDEXING forever — no worker exists to observe a pause flag.
 * Park those orphans as PAUSED so users can resume from persisted progress.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IndexingRecovery implements ApplicationRunner {

    private final RepositoryRepository repositoryRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Repository> orphaned = repositoryRepository.findByIndexStatus(IndexStatus.INDEXING);
        for (Repository repo : orphaned) {
            repo.setIndexStatus(IndexStatus.PAUSED);
            repo.setErrorMessage(null);
            repo.setUpdatedAt(Instant.now());
        }
        if (!orphaned.isEmpty()) {
            repositoryRepository.saveAll(orphaned);
            log.info("Parked {} orphaned indexing job(s) as PAUSED", orphaned.size());
        }
    }
}
