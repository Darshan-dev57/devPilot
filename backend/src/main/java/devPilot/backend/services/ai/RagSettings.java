package devPilot.backend.services.ai;

public final class RagSettings {
     /** How many code chunks to fetch from the vector database per question. */
    public static final int TOP_K_CHUNKS = 8;

    /** Max time (ms) to keep an SSE stream open while the model is responding. */
    public static final long STREAM_TIMEOUT_MS = 180_000L;

    /** Metadata keys stored on each embedded chunk (written by CodeChunker). */
    public static final String METADATA_REPO_ID = "repoId";
    public static final String METADATA_FILE_PATH = "filePath";

    private RagSettings() {
    }
}
