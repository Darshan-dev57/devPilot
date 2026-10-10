package devPilot.backend.exceptions;

/** A daily free limit was reached; maps to HTTP 429. */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
