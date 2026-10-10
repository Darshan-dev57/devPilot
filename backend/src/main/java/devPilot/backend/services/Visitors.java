package devPilot.backend.services;

import java.util.UUID;
import java.util.regex.Pattern;

import devPilot.backend.exceptions.BadRequestException;

/** Parses the X-Visitor-Id header: a random UUID the browser makes once and keeps. */
public final class Visitors {

    public static final String HEADER = "X-Visitor-Id";

    /** The client looks for "visitor id" in this message to know it should make a new id. */
    public static final String INVALID_MESSAGE = "Missing or invalid visitor id. Reload the page and try again.";

    /**
     * The canonical 8-4-4-4-12 hex form. {@link UUID#fromString} also accepts short forms
     * such as "1-2-3-4-5", which the browser would never have made.
     */
    private static final Pattern CANONICAL = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private Visitors() {
    }

    public static UUID parse(String value) {
        String id = value == null ? "" : value.trim();
        if (!CANONICAL.matcher(id).matches()) {
            throw new BadRequestException(INVALID_MESSAGE);
        }
        return UUID.fromString(id);
    }
}
