package devPilot.backend.services.ai;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.http.HttpClient;

import lombok.RequiredArgsConstructor;

/**
 * Builds OpenAI-backed AI models for a specific user's API key (bring-your-own-key).
 *
 * <p>Spring AI's auto-configured singleton beans always use the server key, so every
 * request that touches the AI (chat, retrieval, indexing) must go through this factory
 * instead. Models/embeddings/table settings match the auto-configured defaults, so all
 * users share one {@code vector_store} table safely.
 */
@Component
@RequiredArgsConstructor
public class AiModelFactory {

    private final JdbcTemplate jdbcTemplate;
    private final RestClient.Builder restClientBuilder;

    @Value("${app.ai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${app.ai.chat-model:gpt-4o-mini}")
    private String chatModelName;

    @Value("${app.ai.embedding-model:text-embedding-3-small}")
    private String embeddingModelName;

    @Value("${app.ai.embedding-dimensions:1536}")
    private int embeddingDimensions;

    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, VectorStore> vectorStores = new ConcurrentHashMap<>();

    private final HttpClient httpClient = SpringAiOpenAiHttpClient.builder().build();

    public ChatModel chatModel(UUID userId, String apiKey) {
        return chatModels.computeIfAbsent(cacheKey(userId, apiKey), k -> buildChatModel(apiKey));
    }

    public VectorStore vectorStore(UUID userId, String apiKey) {
        return vectorStores.computeIfAbsent(cacheKey(userId, apiKey), k -> buildVectorStore(apiKey));
    }

    /** Drop cached clients, e.g. after the user changes or removes their key. */
    public void evict(UUID userId) {
        String prefix = userId + ":";
        chatModels.keySet().removeIf(k -> k.startsWith(prefix));
        vectorStores.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static String cacheKey(UUID userId, String apiKey) {
        return userId + ":" + apiKey.hashCode();
    }

    private OpenAIClient client(String apiKey) {
        return new OpenAIClientImpl(ClientOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .httpClient(httpClient)
                .build());
    }

    private ChatModel buildChatModel(String apiKey) {
        return OpenAiChatModel.builder()
                .openAiClient(client(apiKey))
                .options(OpenAiChatOptions.builder()
                        .model(chatModelName)
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .build())
                .build();
    }

    private VectorStore buildVectorStore(String apiKey) {
        EmbeddingModel embeddingModel;
        if (embeddingModelName.startsWith("gemini-")) {
            embeddingModel = new GeminiEmbeddingModel(
                    apiKey, embeddingModelName, embeddingDimensions, restClientBuilder.build());
        } else {
            embeddingModel = OpenAiEmbeddingModel.builder()
                    .openAiClient(client(apiKey))
                    .options(OpenAiEmbeddingOptions.builder()
                            .model(embeddingModelName)
                            .dimensions(embeddingDimensions)
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .build())
                    .build();
        }

        PgVectorStore store = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName("public")
                .vectorTableName("vector_store")
                .initializeSchema(true)
                .dimensions(embeddingDimensions)
                .build();
        // Manually built stores are not Spring beans, so trigger schema init explicitly.
        store.afterPropertiesSet();
        return store;
    }
}
