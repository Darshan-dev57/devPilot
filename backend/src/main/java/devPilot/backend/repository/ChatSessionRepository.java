package devPilot.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import devPilot.backend.entity.ChatSession;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {
    List<ChatSession> findByRepositoryIdAndVisitorIdOrderByCreatedAtDesc(UUID repositoryId, UUID visitorId);

    List<ChatSession> findByRepositoryIdAndVisitorId(UUID repositoryId, UUID visitorId);

    List<ChatSession> findByRepositoryId(UUID repositoryId);
}
