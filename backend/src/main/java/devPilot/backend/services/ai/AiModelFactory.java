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
 * Builds AI models for a specific user's own API key and provider
 * (bring-your-own-key). The user picks OpenAI or Gemini in Settings.
 *
 * <p>Spring AI's auto-configured singleton beans always use the server key, so every
 * request that touches the AI (chat, retrieval, indexing) must go through this factory
 * instead. Each provider gets its own vector table because embedding dimensions differ
 * (OpenAI 1536, Gemini 768).
 */
@Component
@RequiredArgsConstructor
public class AiModelFactory {

    private final JdbcTemplate jdbcTemplate;
    private final RestClient.Builder restClientBuilder;

    // OpenAI group (legacy app.ai.* keys still work as fallback).
    @Value("${app.ai.openai.base-url:${app.ai.base-url:https://api.openai.com}}")
    private String openaiBaseUrl;

    @Value("${app.ai.openai.chat-model:${app.ai.chat-model:gpt-4o-mini}}")
    private String openaiChatModel;

    @Value("${app.ai.openai.embedding-model:${app.ai.embedding-model:text-embedding-3-small}}")
    private String openaiEmbeddingModel;

    @Value("${app.ai.openai.embedding-dimensions:${app.ai.embedding-dimensions:1536}}")
    private int openaiEmbeddingDimensions;

    // Gemini group.
    @Value("${app.ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta/openai/}")
    private String geminiBaseUrl;

    @Value("${app.ai.gemini.chat-model:gemini-3.8-flash}")
    private String geminiChatModel;

    @Value("${app.ai.gemini.embedding-model:gemini-embedding-001}")
    private String geminiEmbeddingModel;

    @Value("${app.ai.gemini.embedding-dimensions:768}")
    private int geminiEmbeddingDimensions;

    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, VectorStore> vectorStores = new ConcurrentHashMap<>();

    private final HttpClient httpClient = SpringAiOpenAiHttpClient.builder().build();

    public ChatModel chatModel(UUID userId, AiProvider provider, String apiKey) {
        return chatModels.computeIfAbsent(
                cacheKey(userId, provider, apiKey), k -> buildChatModel(provider, apiKey));
    }

    public VectorStore vectorStore(UUID userId, AiProvider provider, String apiKey) {
        return vectorStores.computeIfAbsent(
                cacheKey(userId, provider, apiKey), k -> buildVectorStore(provider, apiKey));
    }

    /** Drop cached clients, e.g. after the user changes or removes their key. */
    public void evict(UUID userId) {
        String prefix = userId + ":";
        chatModels.keySet().removeIf(k -> k.startsWith(prefix));
        vectorStores.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static String cacheKey(UUID userId, AiProvider provider, String apiKey) {
        return userId + ":" + provider + ":" + apiKey.hashCode();
    }

    private OpenAIClient client(String baseUrl, String apiKey) {
        return new OpenAIClientImpl(ClientOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .httpClient(httpClient)
                .build());
    }

    private ChatModel buildChatModel(AiProvider provider, String apiKey) {
        if (provider == AiProvider.GEMINI) {
            return OpenAiChatModel.builder()
                    .openAiClient(client(geminiBaseUrl, apiKey))
                    .options(OpenAiChatOptions.builder()
                            .model(geminiChatModel)
                            .apiKey(apiKey)
                            .baseUrl(geminiBaseUrl)
                            .build())
                    .build();
        }
        return OpenAiChatModel.builder()
                .openAiClient(client(openaiBaseUrl, apiKey))
                .options(OpenAiChatOptions.builder()
                        .model(openaiChatModel)
                        .apiKey(apiKey)
                        .baseUrl(openaiBaseUrl)
                        .build())
                .build();
    }

    private VectorStore buildVectorStore(AiProvider provider, String apiKey) {
        EmbeddingModel embeddingModel;
        int dimensions;
        if (provider == AiProvider.GEMINI) {
            dimensions = geminiEmbeddingDimensions;
            embeddingModel = new GeminiEmbeddingModel(
                    apiKey, geminiEmbeddingModel, dimensions, restClientBuilder.build());
        } else {
            dimensions = openaiEmbeddingDimensions;
            embeddingModel = OpenAiEmbeddingModel.builder()
                    .openAiClient(client(openaiBaseUrl, apiKey))
                    .options(OpenAiEmbeddingOptions.builder()
                            .model(openaiEmbeddingModel)
                            .dimensions(dimensions)
                            .apiKey(apiKey)
                            .baseUrl(openaiBaseUrl)
                            .build())
                    .build();
        }

        PgVectorStore store = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName("public")
                .vectorTableName(provider.tableName())
                .initializeSchema(true)
                .dimensions(dimensions)
                .build();
        // Manually built stores are not Spring beans, so trigger schema init explicitly.
        store.afterPropertiesSet();
        return store;
    }
}
