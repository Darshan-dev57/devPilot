package devPilot.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SaveOpenAiKeyRequest(
    @NotBlank(message = "API key must not be blank")
    @Size(max = 1000, message = "API key is too long")
    @Pattern(regexp = "sk-.*", message = "API key must start with sk-")
    String apiKey
) {
}
