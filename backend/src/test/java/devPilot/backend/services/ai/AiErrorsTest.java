package devPilot.backend.services.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class AiErrorsTest {

    @Test
    void badKeyIsNamedAsSuch() {
        var error = HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null,
                "{\"error\":{\"code\":\"invalid_api_key\"}}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(AiErrors.describe(error, AiProvider.OPENAI)).isEqualTo("Your OpenAI API key was rejected. Check it in Settings.");
    }

    @Test
    void quotaAndRateLimitsAreExplained() {
        assertThat(AiErrors.describe(new RuntimeException("RESOURCE_EXHAUSTED: quota"), AiProvider.GEMINI))
                .startsWith("Your Gemini account hit its rate limit");
    }

    @Test
    void unknownErrorsDoNotLeakTheRawBody() {
        String message = AiErrors.describe(new IllegalStateException("{\"secret\":\"internal trace\"}"), AiProvider.OPENAI);
        assertThat(message).doesNotContain("secret").startsWith("OpenAI returned an error");
    }
}
