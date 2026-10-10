package devPilot.backend.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;

class GlobalExceptionHandlerTest {

    @Test
    void aMissingQueryParameterIsABadRequestNotAServerError() {
        var response = new GlobalExceptionHandler()
                .handleMissingParameter(new MissingServletRequestParameterException("repositoryId", "UUID"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Missing parameter repositoryId");
    }

    private static FieldError error(String field, String message) {
        return new FieldError("request", field, message);
    }

    @Test
    void validationDefaultsBecomeReadableSentences() {
        assertThat(GlobalExceptionHandler.readable(error("content", "must not be blank")))
                .isEqualTo("Content is required.");
        assertThat(GlobalExceptionHandler.readable(error("repositoryId", "must not be null")))
                .isEqualTo("Repository id is required.");
        assertThat(GlobalExceptionHandler.readable(error("title", "size must be between 0 and 200")))
                .isEqualTo("Title size must be between 0 and 200.");
        assertThat(GlobalExceptionHandler.readable(error("content", null))).isEqualTo("Content is not valid.");
    }

    @Test
    void messagesWrittenOnTheRequestPassThrough() {
        assertThat(GlobalExceptionHandler.readable(error("owner", "Use owner/name, for example facebook/react")))
                .isEqualTo("Use owner/name, for example facebook/react");
    }
}
