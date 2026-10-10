package devPilot.backend.services.limits;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import devPilot.backend.exceptions.TooManyRequestsException;

/**
 * Daily free limits that protect the server's one AI key.
 *
 * <p>Each visitor gets a number of questions and index runs per day, and the whole site
 * has a global cap on top. Counters live in Postgres, so a restart does not reset them.
 * The day rolls over at midnight Pacific time by default, the same moment Google resets
 * the Gemini free tier's requests-per-day quota. A limit of 0 or less means unlimited.
 */
@Service
public class UsageLimiter {

    public enum Kind {
        QUESTION, INDEX
    }

    public static final String GLOBAL = "global";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;
    private final int questionsPerVisitor;
    private final int indexesPerVisitor;
    private final int questionsGlobal;
    private final int indexesGlobal;

    @Autowired
    public UsageLimiter(
            JdbcTemplate jdbc,
            @Value("${app.limits.zone:America/Los_Angeles}") String zone,
            @Value("${app.limits.questions-per-visitor:30}") int questionsPerVisitor,
            @Value("${app.limits.indexes-per-visitor:5}") int indexesPerVisitor,
            @Value("${app.limits.questions-global:1000}") int questionsGlobal,
            @Value("${app.limits.indexes-global:100}") int indexesGlobal) {
        this(jdbc, Clock.systemUTC(), ZoneId.of(zone), questionsPerVisitor, indexesPerVisitor, questionsGlobal, indexesGlobal);
    }

    public UsageLimiter(JdbcTemplate jdbc, Clock clock, ZoneId zone, int questionsPerVisitor, int indexesPerVisitor,
            int questionsGlobal, int indexesGlobal) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = zone;
        this.questionsPerVisitor = questionsPerVisitor;
        this.indexesPerVisitor = indexesPerVisitor;
        this.questionsGlobal = questionsGlobal;
        this.indexesGlobal = indexesGlobal;
    }

    private volatile boolean tableReady;

    /** Created on first use, so the app still boots when the database is briefly away. */
    public void createTable() {
        if (tableReady) {
            return;
        }
        jdbc.execute("""
                create table if not exists usage_counter (
                    day date not null,
                    subject varchar(64) not null,
                    kind varchar(16) not null,
                    used integer not null,
                    primary key (day, subject, kind))""");
        tableReady = true;
    }

    /**
     * Takes one unit for this visitor and one from the global pool, or throws a 429 with a
     * friendly message and takes nothing.
     */
    public void consume(UUID visitorId, Kind kind) {
        LocalDate day = today();
        String visitor = visitorId.toString();
        if (!take(day, visitor, kind, visitorLimit(kind))) {
            throw new TooManyRequestsException(kind == Kind.QUESTION
                    ? "You've used today's " + visitorLimit(kind) + " free questions. Come back tomorrow, or use your own key."
                    : "You've used today's " + visitorLimit(kind) + " free index runs. Come back tomorrow, or use your own key.");
        }
        if (!take(day, GLOBAL, kind, globalLimit(kind))) {
            give(day, visitor, kind);
            throw new TooManyRequestsException(
                    "DevPilot's free daily limit is used up for everyone today. Try again tomorrow, or use your own key.");
        }
    }

    /** Gives a unit back, for work that never reached the AI provider. */
    public void refund(UUID visitorId, Kind kind) {
        LocalDate day = today();
        give(day, visitorId.toString(), kind);
        give(day, GLOBAL, kind);
    }

    public Usage usage(UUID visitorId) {
        LocalDate day = today();
        return new Usage(
                remaining(day, visitorId.toString(), Kind.QUESTION),
                questionsPerVisitor,
                remaining(day, visitorId.toString(), Kind.INDEX),
                indexesPerVisitor);
    }

    private int remaining(LocalDate day, String subject, Kind kind) {
        int limit = visitorLimit(kind);
        if (limit <= 0) {
            return -1;
        }
        int mine = Math.max(0, limit - used(day, subject, kind));
        int global = globalLimit(kind);
        if (global <= 0) {
            return mine;
        }
        return Math.min(mine, Math.max(0, global - used(day, GLOBAL, kind)));
    }

    private boolean take(LocalDate day, String subject, Kind kind, int limit) {
        if (limit <= 0) {
            return true;
        }
        createTable();
        // One statement, so two requests at the same moment can never both take the last unit.
        List<Integer> rows = jdbc.queryForList("""
                insert into usage_counter (day, subject, kind, used) values (?, ?, ?, 1)
                on conflict (day, subject, kind) do update set used = usage_counter.used + 1
                where usage_counter.used < ?
                returning used""", Integer.class, day, subject, kind.name(), limit);
        return !rows.isEmpty() && rows.get(0) <= limit;
    }

    private void give(LocalDate day, String subject, Kind kind) {
        createTable();
        jdbc.update("update usage_counter set used = used - 1 where day = ? and subject = ? and kind = ? and used > 0",
                day, subject, kind.name());
    }

    private int used(LocalDate day, String subject, Kind kind) {
        createTable();
        List<Integer> rows = jdbc.queryForList(
                "select used from usage_counter where day = ? and subject = ? and kind = ?",
                Integer.class, day, subject, kind.name());
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private int visitorLimit(Kind kind) {
        return kind == Kind.QUESTION ? questionsPerVisitor : indexesPerVisitor;
    }

    private int globalLimit(Kind kind) {
        return kind == Kind.QUESTION ? questionsGlobal : indexesGlobal;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    /** Remaining counts are -1 when that limit is switched off. */
    public record Usage(int questionsLeft, int questionsPerDay, int indexesLeft, int indexesPerDay) {
    }
}
