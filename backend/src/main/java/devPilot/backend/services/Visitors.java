package devPilot.backend.services;

import java.util.UUID;

import devPilot.backend.exceptions.BadRequestException;

/** Parses the X-Visitor-Id header: a random UUID the browser makes once and keeps. */
public final class Visitors {

    public static final String HEADER = "X-Visitor-Id";

    private Visitors() {
    }

    public static UUID parse(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Missing or invalid visitor id. Reload the page and try again.");
        }
    }
}
