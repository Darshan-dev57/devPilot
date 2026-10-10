package devPilot.backend.services.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import devPilot.backend.exceptions.TooManyRequestsException;
import devPilot.backend.services.limits.UsageLimiter.Kind;

@Testcontainers(disabledWithoutDocker = true)
class UsageLimiterTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");

    JdbcTemplate jdbc;
    MutableClock clock;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        // 2026-10-10 12:00 in Los Angeles
        clock = new MutableClock(Instant.parse("2026-10-10T19:00:00Z"));
        limiter(1, 1, 1, 1).createTable();
        jdbc.update("delete from usage_counter");
    }

    UsageLimiter limiter(int questions, int indexes, int globalQuestions, int globalIndexes) {
        return new UsageLimiter(jdbc, clock, PACIFIC, questions, indexes, globalQuestions, globalIndexes);
    }

    @Test
    void aVisitorGetsExactlyTheirDailyQuestions() {
        UsageLimiter limiter = limiter(3, 1, 100, 100);
        UUID visitor = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            limiter.consume(visitor, Kind.QUESTION);
        }
        assertThatThrownBy(() -> limiter.consume(visitor, Kind.QUESTION))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("You've used today's 3 free questions. Come back tomorrow, or use your own key.");
        assertThat(limiter.usage(visitor).questionsLeft()).isZero();
        // Index runs are counted separately.
        limiter.consume(visitor, Kind.INDEX);
        assertThatThrownBy(() -> limiter.consume(visitor, Kind.INDEX)).hasMessageContaining("free index runs");
    }

    @Test
    void visitorsDoNotShareTheirAllowance() {
        UsageLimiter limiter = limiter(1, 1, 100, 100);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        limiter.consume(a, Kind.QUESTION);
        limiter.consume(b, Kind.QUESTION);
        assertThat(limiter.usage(a).questionsLeft()).isZero();
        assertThat(limiter.usage(UUID.randomUUID()).questionsLeft()).isEqualTo(1);
    }

    @Test
    void theGlobalCapStopsEveryoneAndDoesNotEatTheVisitorsAllowance() {
        UsageLimiter limiter = limiter(10, 10, 2, 100);
        limiter.consume(UUID.randomUUID(), Kind.QUESTION);
        limiter.consume(UUID.randomUUID(), Kind.QUESTION);
        UUID late = UUID.randomUUID();
        assertThatThrownBy(() -> limiter.consume(late, Kind.QUESTION))
                .hasMessage("DevPilot's free daily limit is used up for everyone today. Try again tomorrow, or use your own key.");
        Integer used = jdbc.queryForObject("select coalesce(sum(used), 0) from usage_counter where subject = ?",
                Integer.class, late.toString());
        assertThat(used).isZero();
        assertThat(limiter.usage(late).questionsLeft()).as("capped by the global pool").isZero();
    }

    @Test
    void countersResetAtMidnightPacific() {
        UsageLimiter limiter = limiter(1, 1, 100, 100);
        UUID visitor = UUID.randomUUID();
        limiter.consume(visitor, Kind.QUESTION);
        clock.set(Instant.parse("2026-10-11T06:59:00Z")); // 23:59 in Los Angeles, same day
        assertThatThrownBy(() -> limiter.consume(visitor, Kind.QUESTION)).isInstanceOf(TooManyRequestsException.class);
        clock.set(Instant.parse("2026-10-11T07:01:00Z")); // 00:01 the next day
        limiter.consume(visitor, Kind.QUESTION);
    }

    @Test
    void refundGivesTheUnitBack() {
        UsageLimiter limiter = limiter(1, 1, 1, 1);
        UUID visitor = UUID.randomUUID();
        limiter.consume(visitor, Kind.INDEX);
        limiter.refund(visitor, Kind.INDEX);
        limiter.consume(visitor, Kind.INDEX);
    }

    @Test
    void zeroMeansUnlimited() {
        UsageLimiter limiter = limiter(0, 0, 0, 0);
        UUID visitor = UUID.randomUUID();
        for (int i = 0; i < 50; i++) {
            limiter.consume(visitor, Kind.QUESTION);
        }
        assertThat(limiter.usage(visitor).questionsLeft()).isEqualTo(-1);
    }

    @Test
    void parallelRequestsNeverGoOverTheLimit() throws Exception {
        UsageLimiter limiter = limiter(5, 1, 100, 100);
        UUID visitor = UUID.randomUUID();
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 40; i++) {
            pool.submit(() -> {
                try {
                    limiter.consume(visitor, Kind.QUESTION);
                    allowed.incrementAndGet();
                } catch (TooManyRequestsException ignored) {
                    // expected for everything past the fifth
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(allowed.get()).isEqualTo(5);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock outer = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId other) {
                    return outer.withZone(other);
                }

                @Override
                public Instant instant() {
                    return outer.now;
                }
            };
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
