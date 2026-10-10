package devPilot.backend.services.github;

import java.time.Instant;

/**
 * A GitHub call that failed for a reason the user should hear about in plain words.
 * The raw GitHub response body is never shown to the user.
 */
public class GitHubException extends RuntimeException {

    public enum Kind {
        NOT_FOUND,
        PRIVATE_REPO,
        EMPTY_REPO,
        RATE_LIMITED,
        BAD_SERVER_TOKEN,
        UNAVAILABLE
    }

    private final Kind kind;
    private final Instant retryAt;

    public GitHubException(Kind kind, String message) {
        this(kind, message, null);
    }

    public GitHubException(Kind kind, String message, Instant retryAt) {
        super(message);
        this.kind = kind;
        this.retryAt = retryAt;
    }

    public Kind getKind() {
        return kind;
    }

    public Instant getRetryAt() {
        return retryAt;
    }
}
