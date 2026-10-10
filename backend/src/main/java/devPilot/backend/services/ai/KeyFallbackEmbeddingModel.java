package devPilot.backend.services.ai;

import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** Embeddings on the server's Gemini keys, moving to the next key when one is stopped. */
public class KeyFallbackEmbeddingModel implements EmbeddingModel {

    private final GeminiKeyPool pool;
    private final List<EmbeddingModel> models;

    public KeyFallbackEmbeddingModel(GeminiKeyPool pool, Function<String, EmbeddingModel> perKey) {
        this.pool = pool;
        this.models = IntStream.range(0, pool.size()).mapToObj(i -> perKey.apply(pool.key(i))).toList();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return pool.call(i -> models.get(i).call(request));
    }

    @Override
    public float[] embed(Document document) {
        return pool.call(i -> models.get(i).embed(document));
    }

    @Override
    public int dimensions() {
        return models.get(0).dimensions();
    }
}
