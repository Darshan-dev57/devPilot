package devPilot.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Provider is validated against AiProvider; key format per provider in UserService. */
public record SaveAiKeyRequest(
    @NotNull(message = "Provider must be openai or gemini")
    String provider,

    @NotBlank(message = "API key must not be blank")
    @Size(max = 1000, message = "API key is too long")
    String apiKey
) {
}
