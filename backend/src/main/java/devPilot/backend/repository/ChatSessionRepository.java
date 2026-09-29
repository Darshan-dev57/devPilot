package devPilot.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import devPilot.backend.entity.ChatSession;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {
    List<ChatSession> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);

    Optional<ChatSession> findById(UUID id);
}
