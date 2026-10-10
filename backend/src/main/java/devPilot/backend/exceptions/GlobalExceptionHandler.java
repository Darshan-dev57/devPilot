package devPilot.backend.exceptions;
import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import devPilot.backend.services.Visitors;
import devPilot.backend.services.github.GitHubException;
import lombok.extern.slf4j.Slf4j;



@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException ex) {
        return error(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("Validation failed");
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<Map<String, Object>> handleTooMany(TooManyRequestsException ex) {
        return error(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<Map<String, Object>> handleUnavailable(ServiceUnavailableException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(GitHubException.class)
    ResponseEntity<Map<String, Object>> handleGitHub(GitHubException ex) {
        HttpStatus status = switch (ex.getKind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PRIVATE_REPO -> HttpStatus.FORBIDDEN;
            case EMPTY_REPO -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case BAD_SERVER_TOKEN, UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
        };
        return error(status, ex.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex) {
        if (Visitors.HEADER.equalsIgnoreCase(ex.getHeaderName())) {
            return error(HttpStatus.BAD_REQUEST, "Missing visitor id. Reload the page and try again.");
        }
        return error(HttpStatus.BAD_REQUEST, "Missing header " + ex.getHeaderName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, "Missing parameter " + ex.getParameterName());
    }

    @ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
    ResponseEntity<Map<String, Object>> handleUnreadable(Exception ex) {
        return error(HttpStatus.BAD_REQUEST, "The request was not in the expected format.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        // Raw exception text can carry upstream bodies or internals; log it, don't return it.
        log.error("Unhandled error", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on the server. Please try again.");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message,
                "timestamp", Instant.now().toString()));
    }
}
