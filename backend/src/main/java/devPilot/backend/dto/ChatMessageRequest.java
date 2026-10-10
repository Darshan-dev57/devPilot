package devPilot.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatMessageRequest(
        @NotBlank(message = "Type a question before sending.") String content) {
}
