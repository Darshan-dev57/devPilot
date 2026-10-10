package devPilot.backend.exceptions;

/** The server is missing configuration it needs (e.g. no AI key); maps to HTTP 503. */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
