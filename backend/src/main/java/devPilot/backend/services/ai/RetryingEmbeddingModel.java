package devPilot.backend.services.ai;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Waits and retries when the provider answers 429.
 *
 * <p>Gemini's free tier counts every text in a batch as one request and allows 100 per
 * minute for gemini-embedding-2 (measured from its own 429 reply, which also says how long
 * to wait). An index job therefore runs into the limit after about 100 chunks; instead of
 * failing, it waits the time Google asks for and carries on. Index jobs wait patiently.
 * A chat question (no wait listener set) only retries briefly, so a visitor is not left
 * staring at a spinner for a minute.
 */
@Slf4j
public class RetryingEmbeddingModel implements EmbeddingModel {

    /** Set by the indexing thread to show "waiting for the free limit" in the progress UI. */
    public static final ThreadLocal<Consumer<String>> WAIT_LISTENER = new ThreadLocal<>();

    private static final Pattern RETRY_IN = Pattern.compile("retry in ([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);
    private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"([0-9]+)s\"");
    private static final long MAX_WAIT_MS = 65_000;
    private static final int BARE_429_ATTEMPTS = 4;

    private final EmbeddingModel delegate;
    private final long baseDelayMs;
    private final int attempts;
    private final long bareWaitMs;

    public RetryingEmbeddingModel(EmbeddingModel delegate, long baseDelayMs, int attempts) {
        this(delegate, baseDelayMs, attempts, Math.max(baseDelayMs, baseDelayMs * 7));
    }

    /** @param bareWaitMs first wait after a 429 that names no wait; doubles each time (15, 30, 60 s by default) */
    public RetryingEmbeddingModel(EmbeddingModel delegate, long baseDelayMs, int attempts, long bareWaitMs) {
        this.delegate = delegate;
        this.baseDelayMs = baseDelayMs;
        this.attempts = Math.max(1, attempts);
        this.bareWaitMs = bareWaitMs;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        Consumer<String> listener = WAIT_LISTENER.get();
        int maxAttempts = listener != null ? attempts : Math.min(attempts, 2);
        for (int attempt = 1; ; attempt++) {
            try {
                return delegate.call(request);
            } catch (RuntimeException ex) {
                if (attempt >= maxAttempts || !isRateLimit(ex)) {
                    throw ex;
                }
                // A per-minute limit usually says how long to wait. A 429 that names no wait
                // and keeps coming for about two minutes is the daily quota: fail with that.
                if (retryHintMs(ex) < 0 && attempt >= BARE_429_ATTEMPTS) {
                    throw new DailyQuotaException(ex);
                }
                long wait = listener != null
                        ? waitMs(ex, bareWaitMs * (1L << (attempt - 1)))
                        : Math.min(baseDelayMs, 3_000);
                log.info("Embedding rate limited, retrying in {} ms (attempt {}/{})", wait, attempt + 1, maxAttempts);
                if (listener != null) {
                    listener.accept("Waiting " + Math.max(1, wait / 1000) + " s for the free per-minute limit");
                }
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    /** The wait the provider asked for, plus a second, else the exponential fallback. */
    static long waitMs(Throwable error, long fallbackMs) {
        long hint = retryHintMs(error);
        return Math.min(MAX_WAIT_MS, hint >= 0 ? hint : fallbackMs);
    }

    /** The provider's "retry in Ns" plus a second, or -1 when the answer names no wait. */
    static long retryHintMs(Throwable error) {
        String text = messages(error);
        Matcher m = RETRY_IN.matcher(text);
        if (m.find()) {
            return (long) (Double.parseDouble(m.group(1)) * 1000) + 1000;
        }
        m = RETRY_DELAY.matcher(text);
        if (m.find()) {
            return Long.parseLong(m.group(1)) * 1000 + 1000;
        }
        return -1;
    }

    /** Thrown when the 429s look like the daily quota rather than the per-minute one. */
    public static class DailyQuotaException extends RuntimeException {
        public DailyQuotaException(Throwable cause) {
            super("429 daily quota used up (no retry time given)", cause);
        }
    }

    static boolean isRateLimit(Throwable error) {
        return AiErrors.isRateLimit(error);
    }

    private static String messages(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            text.append(' ').append(t.getMessage());
            if (t instanceof org.springframework.web.client.RestClientResponseException rest) {
                text.append(' ').append(rest.getResponseBodyAsString());
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return text.toString();
    }

    @Override
    public float[] embed(Document document) {
        return delegate.embed(document);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
