package devPilot.backend.dto;

import jakarta.validation.constraints.Pattern;

public record AddPublicRepoRequest(
    @Pattern(regexp = "^[A-Za-z0-9_.-]{1,100}$", message = "owner must be owner/name format")
    String owner,

    @Pattern(regexp = "^[A-Za-z0-9_.-]{1,100}$", message = "name must be owner/name format")
    String name
) {
}
