package devPilot.backend.services.ai;

import java.util.Locale;

import org.springframework.web.client.RestClientResponseException;

/**
 * Turns errors from the AI provider into one short sentence a visitor can act on.
 * Provider response bodies are never passed through verbatim. The key belongs to the
 * server owner, so key problems are worded for them, not for the visitor.
 */
public final class AiErrors {

    private AiErrors() {
    }

    public static String describe(Throwable error, AiProvider provider) {
        return describe(error, provider, false);
    }

    /** @param visitorKey true when the call ran on the visitor's own key, so the wording is for them */
    public static String describe(Throwable error, AiProvider provider, boolean visitorKey) {
        String name = provider.label();
        Integer status = status(error);
        String lower = text(error);

        if (provider == AiProvider.OPENCODE && (lower.contains("freetiererror") || lower.contains("only be used from within opencode"))) {
            return "OpenCode Zen refused: its free models only work inside the OpenCode app. Pick a paid model in "
                    + "\"Use your own key\", or use a Gemini key.";
        }
        if (eq(status, 401) || eq(status, 403) || lower.contains("invalid_api_key") || lower.contains("incorrect api key")
                || lower.contains("api key not valid") || lower.contains("api_key_invalid") || lower.contains("permission_denied")) {
            return visitorKey
                    ? "Your " + name + " key was rejected. Check it in \"Use your own key\"."
                    : "The server's " + name + " key was rejected. The site owner needs to check it.";
        }
        if (visitorKey && (eq(status, 402) || lower.contains("insufficient_quota") || lower.contains("insufficient balance")
                || lower.contains("billing"))) {
            return "Your " + name + " account has no credit left. Top it up or remove your key to use the free allowance.";
        }
        if (isRateLimit(error)) {
            if (visitorKey) {
                return "Your " + name + " key hit its rate limit. Wait a minute and try again.";
            }
            if (lower.contains("perday") || lower.contains("per day") || lower.contains("daily")) {
                // DailyQuotaException says "daily quota"; Google's per-day quota ids say PerDay.
                return "The free " + name + " quota for today is used up. Please try again tomorrow.";
            }
            return "The free " + name + " quota is busy right now. Wait a minute and try again.";
        }
        if (provider == AiProvider.OPENCODE && (eq(status, 404) || eq(status, 400) || lower.contains("model"))) {
            return "OpenCode Zen does not offer that model on its chat endpoint. Pick another model in \"Use your own key\".";
        }
        if (eq(status, 404) || lower.contains("model_not_found") || lower.contains("does not exist")
                || lower.contains("not found")) {
            return visitorKey
                    ? "The " + name + " model is not available for your key."
                    : "The configured " + name + " model is not available. The site owner needs to check the model name.";
        }
        if (lower.contains("timed out") || lower.contains("timeout") || lower.contains("connect")) {
            return name + " did not respond in time. Try again.";
        }
        return name + " returned an error" + (status != null ? " (HTTP " + status + ")" : "") + ". Try again.";
    }

    /** True for HTTP 429 or a provider's quota/rate-limit wording. */
    public static boolean isRateLimit(Throwable error) {
        String lower = text(error);
        return eq(status(error), 429) || lower.contains("insufficient_quota") || lower.contains("rate limit")
                || lower.contains("rate_limit") || lower.contains("resource_exhausted") || lower.contains("quota")
                || lower.contains(" 429");
    }

    private static Integer status(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof RestClientResponseException rest) {
                return rest.getStatusCode().value();
            }
            Integer sdk = statusFromSdk(t);
            if (sdk != null) {
                return sdk;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    private static String text(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                text.append(' ').append(t.getMessage());
            }
            if (t instanceof RestClientResponseException rest) {
                text.append(' ').append(rest.getResponseBodyAsString());
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return text.toString().toLowerCase(Locale.ROOT);
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
