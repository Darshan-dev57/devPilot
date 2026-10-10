package devPilot.backend.dto;

import jakarta.validation.constraints.Pattern;

public record AddPublicRepoRequest(
    @Pattern(regexp = "^[A-Za-z0-9_.-]{1,100}$", message = "Use owner/name, for example facebook/react")
    String owner,

    @Pattern(regexp = "^[A-Za-z0-9_.-]{1,100}$", message = "Use owner/name, for example facebook/react")
    String name
) {
}
