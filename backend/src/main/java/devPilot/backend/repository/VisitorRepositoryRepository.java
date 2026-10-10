package devPilot.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import devPilot.backend.entity.VisitorRepository;

public interface VisitorRepositoryRepository extends JpaRepository<VisitorRepository, UUID> {
    List<VisitorRepository> findByVisitorIdOrderByCreatedAtDesc(UUID visitorId);

    boolean existsByVisitorIdAndRepositoryId(UUID visitorId, UUID repositoryId);

    long countByRepositoryId(UUID repositoryId);

    void deleteByVisitorIdAndRepositoryId(UUID visitorId, UUID repositoryId);
}
