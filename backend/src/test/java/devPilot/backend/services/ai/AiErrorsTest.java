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

    @Test
    void aVisitorsGeminiFreeTierCapIsAQuotaNotAnEmptyBalance() {
        // What Google answered on 2026-10-10 once a free key's 1000 daily embedding calls were spent.
        var daily = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null,
                ("{\"error\":{\"code\":429,\"message\":\"You exceeded your current quota, please check your plan and billing details. "
                        + "* Quota exceeded for metric: generativelanguage.googleapis.com/embed_content_free_tier_requests, limit: 1000\","
                        + "\"status\":\"RESOURCE_EXHAUSTED\"}}").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(AiErrors.describe(daily, AiProvider.GEMINI, true))
                .isEqualTo("Your Gemini key used up its free daily quota. Try again tomorrow, or use another key.");
        assertThat(AiErrors.describe(new RuntimeException("429 rate limit"), AiProvider.GEMINI, true))
                .isEqualTo("Your Gemini key hit its rate limit. Wait a minute and try again.");
    }

    @Test
    void providerHiccupsAreRetried() {
        var error = HttpClientErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Gemini error", null,
                "{\"error\":{\"code\":500,\"status\":\"INTERNAL\"}}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(AiErrors.isTransient(error)).isTrue();
        assertThat(AiErrors.isTransient(new RuntimeException("invalid x-api-key"))).isFalse();
    }
}
