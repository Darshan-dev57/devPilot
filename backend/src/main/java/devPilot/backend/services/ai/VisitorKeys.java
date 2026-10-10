package devPilot.backend.services.ai;

/**
 * Keys a visitor chose to use instead of the server's free allowance. They arrive in the
 * X-Gemini-Key and X-OpenCode-Key headers (plus an optional X-OpenCode-Model) on each request, live only for that request
 * (or that index job) and are never stored or logged.
 */
public record VisitorKeys(String gemini, String opencode, String opencodeModel) {

    public static final String GEMINI_HEADER = "X-Gemini-Key";
    public static final String OPENCODE_HEADER = "X-OpenCode-Key";
    public static final String OPENCODE_MODEL_HEADER = "X-OpenCode-Model";
    public static final VisitorKeys NONE = new VisitorKeys(null, null, null);

    public VisitorKeys {
        gemini = clean(gemini);
        opencode = clean(opencode);
        opencodeModel = clean(opencodeModel);
    }

    public boolean hasGemini() {
        return !gemini.isEmpty();
    }

    public boolean hasOpencode() {
        return !opencode.isEmpty();
    }

    public boolean any() {
        return hasGemini() || hasOpencode();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        return "VisitorKeys[gemini=" + (hasGemini() ? "set" : "none") + ", opencode=" + (hasOpencode() ? "set" : "none") + "]";
    }
}
