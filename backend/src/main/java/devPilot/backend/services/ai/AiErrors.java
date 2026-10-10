package devPilot.backend.services.ai;

import java.util.Locale;

import org.springframework.web.client.RestClientResponseException;

/**
 * Turns errors from OpenAI or Gemini into one short sentence a visitor can act on.
 * Provider response bodies are never passed through verbatim.
 */
public final class AiErrors {

    private AiErrors() {
    }

    public static String describe(Throwable error, AiProvider provider) {
        String name = provider == AiProvider.GEMINI ? "Gemini" : "OpenAI";
        Integer status = null;
        StringBuilder text = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof RestClientResponseException rest && status == null) {
                status = rest.getStatusCode().value();
            }
            if (status == null) {
                status = statusFromSdk(t);
            }
            if (t.getMessage() != null) {
                text.append(' ').append(t.getMessage());
            }
            if (t.getCause() == t) {
                break;
            }
        }
        String lower = text.toString().toLowerCase(Locale.ROOT);

        if (eq(status, 401) || eq(status, 403) || lower.contains("invalid_api_key") || lower.contains("incorrect api key")
                || lower.contains("api key not valid") || lower.contains("api_key_invalid") || lower.contains("permission_denied")) {
            return "Your " + name + " API key was rejected. Check it in Settings.";
        }
        if (eq(status, 429) || lower.contains("insufficient_quota") || lower.contains("rate limit")
                || lower.contains("resource_exhausted") || lower.contains("quota")) {
            return "Your " + name + " account hit its rate limit or has no credit left. Wait a minute or check billing.";
        }
        if (eq(status, 404) || lower.contains("model_not_found") || lower.contains("does not exist")) {
            return "The " + name + " model is not available for your key.";
        }
        if (lower.contains("timed out") || lower.contains("timeout") || lower.contains("connect")) {
            return name + " did not respond in time. Try again.";
        }
        return name + " returned an error" + (status != null ? " (HTTP " + status + ")" : "") + ". Try again.";
    }

    private static boolean eq(Integer status, int expected) {
        return status != null && status == expected;
    }

    /** The OpenAI Java SDK throws its own exception types that expose statusCode(). */
    private static Integer statusFromSdk(Throwable t) {
        try {
            var method = t.getClass().getMethod("statusCode");
            Object value = method.invoke(t);
            return value instanceof Number n ? n.intValue() : null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }
}
