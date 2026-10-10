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
        assertThat(AiErrors.describe(error, AiProvider.OPENAI)).isEqualTo("The server's OpenAI key was rejected. The site owner needs to check it.");
    }

    @Test
    void quotaAndRateLimitsAreExplained() {
        assertThat(AiErrors.describe(new RuntimeException("RESOURCE_EXHAUSTED: quota"), AiProvider.GEMINI))
                .isEqualTo("The free Gemini quota is busy right now. Wait a minute and try again.");
        var daily = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null,
                "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"quotaId\":\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\"}]}}"
                        .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(AiErrors.isRateLimit(daily)).isTrue();
        assertThat(AiErrors.describe(daily, AiProvider.GEMINI))
                .isEqualTo("The free Gemini quota for today is used up. Please try again tomorrow.");
    }

    @Test
    void unknownErrorsDoNotLeakTheRawBody() {
        String message = AiErrors.describe(new IllegalStateException("{\"secret\":\"internal trace\"}"), AiProvider.OPENAI);
        assertThat(message).doesNotContain("secret").startsWith("OpenAI returned an error");
    }
}
