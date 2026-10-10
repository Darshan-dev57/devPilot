package devPilot.backend.services.ai;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * The server's Gemini keys. GEMINI_API_KEY may hold several keys separated by commas.
 *
 * <p>Requests go to one key until Google stops it: a daily cap or a rejected key benches
 * that key until midnight Pacific (when Google resets daily quotas), and a per-minute 429
 * just moves the same request on to the next key. When every key is used up, callers get
 * {@link RetryingEmbeddingModel.DailyQuotaException}, which reads as the usual "free quota
 * for today is used up" message.
 *
 * <p>Free quota is per Google Cloud project, so the keys only add quota when each comes
 * from a different project. All keys use the same embedding model, so vectors stay
 * compatible whichever key made them. Key values are never logged, only their position.
 */
@Slf4j
@Component
public class GeminiKeyPool {

    private enum Failure { REJECTED, DAILY, MINUTE, OTHER }

    private final List<String> keys;
    private final Clock clock;
    private final ZoneId zone;
    private final Map<Integer, Bench> benched = new ConcurrentHashMap<>();
    private final AtomicInteger current = new AtomicInteger();

    private record Bench(Instant until, boolean quota) {
    }

    @Autowired
    public GeminiKeyPool(@Value("${app.ai.gemini.api-key:}") String keys,
            @Value("${app.limits.zone:America/Los_Angeles}") String zone) {
        this(parse(keys), Clock.systemUTC(), ZoneId.of(zone));
    }

    public GeminiKeyPool(List<String> keys, Clock clock, ZoneId zone) {
        this.keys = List.copyOf(keys);
        this.clock = clock;
        this.zone = zone;
    }

    /** "k1, k2,k3" (or one per line) becomes [k1, k2, k3]; blanks and repeats are dropped. */
    public static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(Arrays.stream(raw.split("[,\\s]+"))
                .filter(k -> !k.isBlank())
                .toList()));
    }

    public int size() {
        return keys.size();
    }

    String key(int index) {
        return keys.get(index);
    }

    /** Keys not benched right now, starting with the one that last worked. */
    List<Integer> available() {
        Instant now = clock.instant();
        benched.values().removeIf(b -> !b.until().isAfter(now));
        List<Integer> order = new ArrayList<>();
        int start = current.get();
        for (int n = 0; n < keys.size(); n++) {
            int i = (start + n) % keys.size();
            if (!benched.containsKey(i)) {
                order.add(i);
            }
        }
        return order;
    }

    /** Runs a blocking call on the first key that works, moving on when a key is stopped. */
    public <T> T call(IntFunction<T> action) {
        Attempts attempts = new Attempts();
        for (int i : available()) {
            try {
                T result = action.apply(i);
                current.set(i);
                return result;
            } catch (RuntimeException ex) {
                if (!attempts.record(i, ex)) {
                    throw ex;
                }
            }
        }
        throw attempts.giveUp();
    }

    /**
     * Streams from the first key that works. A key is only swapped before any text went
     * out, so an answer never repeats.
     */
    public <T> Flux<T> stream(IntFunction<Flux<T>> action) {
        return Flux.defer(() -> stream(action, available(), 0, new Attempts()));
    }

    private <T> Flux<T> stream(IntFunction<Flux<T>> action, List<Integer> order, int pos, Attempts attempts) {
        if (pos >= order.size()) {
            return Flux.error(attempts.giveUp());
        }
        int i = order.get(pos);
        AtomicBoolean started = new AtomicBoolean();
        return action.apply(i)
                .doOnNext(item -> {
                    if (!started.getAndSet(true)) {
                        current.set(i);
                    }
                })
                .doOnComplete(() -> current.set(i))
                .onErrorResume(ex -> !started.get() && attempts.record(i, ex),
                        ex -> stream(action, order, pos + 1, attempts));
    }

    private static Failure classify(Throwable ex) {
        if (ex instanceof RetryingEmbeddingModel.DailyQuotaException) {
            return Failure.DAILY;
        }
        if (AiErrors.isKeyRejected(ex)) {
            return Failure.REJECTED;
        }
        if (AiErrors.isRateLimit(ex)) {
            return RetryingEmbeddingModel.isDailyCap(ex) ? Failure.DAILY : Failure.MINUTE;
        }
        return Failure.OTHER;
    }

    private void bench(int i, boolean quota) {
        LocalDate tomorrow = LocalDate.now(clock.withZone(zone)).plusDays(1);
        benched.put(i, new Bench(tomorrow.atStartOfDay(zone).toInstant(), quota));
        log.warn("Gemini key #{} of {} {}; skipping it until midnight Pacific", i + 1, keys.size(),
                quota ? "used up its daily quota" : "was rejected");
    }

    /** What happened on each key during one request, and the error to show when all fail. */
    private final class Attempts {
        private RuntimeException minute;
        private RuntimeException quota;
        private RuntimeException rejected;

        /** @return true when the next key should be tried */
        boolean record(int i, Throwable error) {
            RuntimeException ex = error instanceof RuntimeException r ? r : new RuntimeException(error);
            switch (classify(ex)) {
                case REJECTED -> {
                    bench(i, false);
                    rejected = ex;
                }
                case DAILY -> {
                    bench(i, true);
                    quota = ex;
                }
                case MINUTE -> {
                    log.info("Gemini key #{} of {} hit the per-minute limit; trying the next one", i + 1, keys.size());
                    minute = ex;
                }
                default -> {
                    return false;
                }
            }
            return true;
        }

        RuntimeException giveUp() {
            // A per-minute limit clears soon: let the caller's usual wait-and-retry handle it.
            if (minute != null) {
                return minute;
            }
            boolean anyQuota = quota != null || benched.values().stream().anyMatch(Bench::quota);
            if (anyQuota) {
                return quota instanceof RetryingEmbeddingModel.DailyQuotaException daily
                        ? daily
                        : new RetryingEmbeddingModel.DailyQuotaException(quota);
            }
            if (rejected != null) {
                return rejected;
            }
            return new IllegalStateException("Every server Gemini key was rejected: api_key_invalid");
        }
    }
}
