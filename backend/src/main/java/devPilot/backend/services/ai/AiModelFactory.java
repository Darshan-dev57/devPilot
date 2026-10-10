package devPilot.backend.services.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

import devPilot.backend.exceptions.BadRequestException;
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
    @Value("${app.ai.openai.base-url:${app.ai.base-url:https://api.openai.com/v1}}")
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

    @Value("${app.ai.gemini.native-base-url:https://generativelanguage.googleapis.com}")
    private String geminiNativeBaseUrl;

    /** Keys are visitors' own; cap how many per-key clients stay cached in memory. */
    private static final int MAX_CACHED_KEYS = 200;

    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, VectorStore> vectorStores = new ConcurrentHashMap<>();

    private final HttpClient httpClient = SpringAiOpenAiHttpClient.builder().build();

    public ChatModel chatModel(AiProvider provider, String apiKey) {
        trimCaches();
        return chatModels.computeIfAbsent(
                cacheKey(provider, apiKey), k -> buildChatModel(provider, apiKey));
    }

    public VectorStore vectorStore(AiProvider provider, String apiKey) {
        trimCaches();
        return vectorStores.computeIfAbsent(
                cacheKey(provider, apiKey), k -> buildVectorStore(provider, apiKey));
    }

    public void evict() {
        chatModels.clear();
        vectorStores.clear();
    }

    private void trimCaches() {
        if (chatModels.size() > MAX_CACHED_KEYS || vectorStores.size() > MAX_CACHED_KEYS) {
            evict();
        }
    }

    /** SHA-256 rather than hashCode(): two different keys must never share a client. */
    private static String cacheKey(AiProvider provider, String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return provider + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Makes one tiny embedding call to prove the key works before any index job starts.
     * Without this, a typo'd key would only fail after the job had already cleared the
     * repo's existing vectors, breaking chat for everyone using that repo.
     */
    public void verifyKey(AiProvider provider, String apiKey) {
        try {
            embeddingModel(provider, apiKey).embed("ping");
        } catch (RuntimeException ex) {
            throw new BadRequestException(AiErrors.describe(ex, provider));
        }
    }

    /** Table holding this provider's vectors (dimensions differ per provider). */
    public String tableName(AiProvider provider) {
        return provider.tableName();
    }

    private OpenAIClient client(String baseUrl, String apiKey) {
        return new OpenAIClientImpl(ClientOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .httpClient(httpClient)
                .build());
    }

    /** Chat model for a visitor's key. Protected so tests can swap in a fake. */
    protected ChatModel buildChatModel(AiProvider provider, String apiKey) {
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

    protected int dimensions(AiProvider provider) {
        return provider == AiProvider.GEMINI ? geminiEmbeddingDimensions : openaiEmbeddingDimensions;
    }

    /** Embedding model for a visitor's key. Protected so tests can swap in a fake. */
    protected EmbeddingModel embeddingModel(AiProvider provider, String apiKey) {
        int dimensions = dimensions(provider);
        if (provider == AiProvider.GEMINI) {
            return new GeminiEmbeddingModel(
                    apiKey, geminiNativeBaseUrl, geminiEmbeddingModel, dimensions, restClientBuilder.clone().build());
        }
        return OpenAiEmbeddingModel.builder()
                    .openAiClient(client(openaiBaseUrl, apiKey))
                    .options(OpenAiEmbeddingOptions.builder()
                            .model(openaiEmbeddingModel)
                            .dimensions(dimensions)
                            .apiKey(apiKey)
                            .baseUrl(openaiBaseUrl)
                            .build())
                    .build();
    }

    private VectorStore buildVectorStore(AiProvider provider, String apiKey) {
        EmbeddingModel embeddingModel = embeddingModel(provider, apiKey);
        int dimensions = dimensions(provider);
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
