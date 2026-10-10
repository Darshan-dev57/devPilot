package devPilot.backend.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record CreateChatSessionRequest(
        @NotNull(message = "Pick a repository to chat with.") UUID repositoryId,
        String title) {
}
