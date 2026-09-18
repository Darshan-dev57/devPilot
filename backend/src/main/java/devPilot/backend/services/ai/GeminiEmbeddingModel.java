package devPilot.backend.services.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Embeddings via the native Gemini API using a plain API key (no GCP setup).
 *
 * <p>Used for Gemini embedding models because Gemini's OpenAI-compatible layer omits
 * the {@code index} field the OpenAI SDK requires, which breaks parsing.
 */
public class GeminiEmbeddingModel extends AbstractEmbeddingModel {

    private static final String EMBED_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:batchEmbedContents";

    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public GeminiEmbeddingModel(String apiKey, String model, int dimensions, RestClient restClient) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = restClient;
        this.embeddingDimensions.set(dimensions);
    }

    @Override
    @SuppressWarnings("unchecked")
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Map<String, Object>> requests = new ArrayList<>();
        for (String text : request.getInstructions()) {
            requests.add(Map.of(
                    "model", "models/" + model,
                    "content", Map.of("parts", List.of(Map.of("text", text))),
                    "outputDimensionality", this.embeddingDimensions.get()));
        }

        Map<String, Object> response = restClient.post()
                .uri(EMBED_URL.formatted(model))
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requests", requests))
                .retrieve()
                .body(Map.class);

        List<Map<String, Object>> embeddings = (List<Map<String, Object>>) response.get("embeddings");
        List<Embedding> result = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            // batchEmbedContents items carry "values" directly (no nested object)
            List<Number> values = (List<Number>) embeddings.get(i).get("values");
            float[] vector = new float[values.size()];
            for (int j = 0; j < values.size(); j++) {
                vector[j] = values.get(j).floatValue();
            }
            result.add(new Embedding(vector, i));
        }
        return new EmbeddingResponse(result);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }
}
