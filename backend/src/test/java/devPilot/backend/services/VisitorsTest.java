package devPilot.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import devPilot.backend.exceptions.BadRequestException;

class VisitorsTest {

    @Test
    void acceptsTheIdTheBrowserMakes() {
        UUID id = UUID.randomUUID();
        assertThat(Visitors.parse(id.toString())).isEqualTo(id);
        assertThat(Visitors.parse(" " + id.toString().toUpperCase() + " ")).isEqualTo(id);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "abc", "1-2-3-4-5", "null", "undefined", "{\"id\":1}",
            "123e4567-e89b-12d3-a456-42661417400g", "123e4567e89b12d3a456426614174000" })
    void rejectsAnythingElseWithAMessageTheClientRecognises(String value) {
        assertThatThrownBy(() -> Visitors.parse(value))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(Visitors.INVALID_MESSAGE)
                .hasMessageContaining("visitor id");
    }
}
