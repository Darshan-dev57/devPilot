package devPilot.backend.services.ai;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.http.HttpClient;

import devPilot.backend.exceptions.ServiceUnavailableException;

/**
 * Builds the chat model, embedding model and pgvector store for a key.
 *
 * <ul>
 *   <li>Gemini (default): Google's native REST API with the server's GEMINI_API_KEY.</li>
 *   <li>OpenAI: Spring AI's OpenAI client with OPENAI_API_KEY.</li>
 *   <li>For a visitor's own key, answers can also come from Claude (Anthropic Messages
 *       API) or OpenCode Zen (OpenAI-compatible endpoint). Both are chat only.</li>
 * </ul>
 * Models for the server's key are cached. Models for a visitor's key are built for one
 * request and dropped, so a visitor's key is never kept or reused.
 */
@Component
public class AiModelFactory {

    private final JdbcTemplate jdbcTemplate;
    private final RestClient.Builder restClientBuilder;

    @Value("${app.ai.openai.base-url:https://api.openai.com/v1}")
    private String openaiBaseUrl;
    @Value("${app.ai.openai.chat-model:gpt-4o-mini}")
    private String openaiChatModel;
    @Value("${app.ai.openai.embedding-model:text-embedding-3-small}")
    private String openaiEmbeddingModel;
    @Value("${app.ai.openai.embedding-dimensions:1536}")
    private int openaiEmbeddingDimensions;

    @Value("${app.ai.gemini.base-url:https://generativelanguage.googleapis.com}")
    private String geminiBaseUrl;
    /** Google expects x-goog-api-key; overridable for a proxy that wants another header. */
    @Value("${app.ai.gemini.key-header:x-goog-api-key}")
    private String geminiKeyHeader;
    @Value("${app.ai.gemini.chat-model:gemini-3.5-flash-lite}")
    private String geminiChatModel;
    @Value("${app.ai.gemini.embedding-model:gemini-embedding-2}")
    private String geminiEmbeddingModel;
    @Value("${app.ai.gemini.embedding-dimensions:768}")
    private int geminiEmbeddingDimensions;

    @Value("${app.ai.opencode.base-url:https://opencode.ai/zen/v1}")
    private String opencodeBaseUrl;
    @Value("${app.ai.opencode.chat-model:deepseek-v4.1-flash}")
    private String opencodeChatModel;

    @Value("${app.ai.anthropic.base-url:https://api.anthropic.com}")
    private String anthropicBaseUrl;
    @Value("${app.ai.anthropic.chat-model:claude-sonnet-5-5}")
    private String anthropicChatModel;
    /** Default model when a visitor answers with their own OpenAI key. */
    @Value("${app.ai.openai.visitor-chat-model:gpt-6-luna}")
    private String openaiVisitorChatModel;

    @Value("${app.ai.retry-base-delay-ms:2000}")
    private long retryBaseDelayMs;

    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, VectorStore> vectorStores = new ConcurrentHashMap<>();
    private final Set<AiProvider> schemaReady = ConcurrentHashMap.newKeySet();
    private final HttpClient httpClient = SpringAiOpenAiHttpClient.builder().build();

    public AiModelFactory(JdbcTemplate jdbcTemplate, RestClient.Builder restClientBuilder) {
        this.jdbcTemplate = jdbcTemplate;
        this.restClientBuilder = restClientBuilder;
    }

    public ChatModel chatModel(AiProvider provider, String apiKey) {
        return chatModels.computeIfAbsent(provider.name(), k -> buildChatModel(provider, apiKey));
    }

    /** Chat model for a key: cached for the server's key, built fresh for a visitor's. */
    public ChatModel chatModel(AiKeyResolver.UserAiKey key) {
        if (!key.visitor()) {
            return chatModel(key.provider(), key.apiKey());
        }
        if (key.model() == null && key.provider() == AiProvider.GEMINI) {
            return buildChatModel(key.provider(), key.apiKey());
        }
        String model = key.model() != null ? key.model() : visitorDefaultModel(key.provider());
        return visitorChatModel(key.provider(), key.apiKey(), model);
    }

    /** The model a visitor's own key answers with when they did not pick one. */
    public String visitorDefaultModel(AiProvider provider) {
        return switch (provider) {
            case GEMINI -> geminiChatModel;
            case OPENAI -> openaiVisitorChatModel;
            case OPENCODE -> opencodeChatModel;
            case ANTHROPIC -> anthropicChatModel;
        };
    }

    /** Protected so tests can swap in a fake. */
    protected ChatModel visitorChatModel(AiProvider provider, String apiKey, String model) {
        return switch (provider) {
            case GEMINI -> new GeminiChatModel(geminiBaseUrl, model, apiKey, geminiKeyHeader);
            case OPENAI -> openAiChat(openaiBaseUrl, apiKey, model);
            case OPENCODE -> openAiChat(opencodeBaseUrl, apiKey, model);
            case ANTHROPIC -> new ClaudeChatModel(anthropicBaseUrl, model, apiKey, 2048);
        };
    }

    public VectorStore vectorStore(AiProvider provider, String apiKey) {
        return vectorStores.computeIfAbsent(provider.name(), k -> buildVectorStore(provider, apiKey));
    }

    /** Vector store for a key: cached for the server's key, built fresh for a visitor's. */
    public VectorStore vectorStore(AiKeyResolver.UserAiKey key) {
        return key.visitor() ? buildVectorStore(key.provider(), key.apiKey()) : vectorStore(key.provider(), key.apiKey());
    }

    /**
     * One tiny embedding call that proves the AI provider answers before an index job
     * starts; otherwise a broken key would only fail after old vectors were cleared.
     */
    public void verifyKey(AiKeyResolver.UserAiKey key) {
        try {
            embeddingModel(key.provider(), key.apiKey()).embed("ping");
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException(AiErrors.describe(ex, key.provider(), key.visitor()));
        }
    }

    public void verifyKey(AiProvider provider, String apiKey) {
        verifyKey(new AiKeyResolver.UserAiKey(provider, apiKey));
    }

    public String chatModelName(AiProvider provider) {
        return switch (provider) {
            case GEMINI -> geminiChatModel;
            case OPENAI -> openaiChatModel;
            case OPENCODE -> opencodeChatModel;
            case ANTHROPIC -> anthropicChatModel;
        };
    }

    public String tableName(AiProvider provider) {
        return provider.tableName();
    }

    protected int dimensions(AiProvider provider) {
        return switch (provider) {
            case GEMINI -> geminiEmbeddingDimensions;
            case OPENAI -> openaiEmbeddingDimensions;
            case OPENCODE, ANTHROPIC -> throw new IllegalStateException(provider.label() + " has no embeddings");
        };
    }

    /** Embedding model for the provider. Protected so tests can swap in a fake. */
    protected EmbeddingModel embeddingModel(AiProvider provider, String apiKey) {
        EmbeddingModel model = switch (provider) {
            case GEMINI -> new GeminiEmbeddingModel(apiKey, geminiBaseUrl, geminiKeyHeader, geminiEmbeddingModel,
                    geminiEmbeddingDimensions, restClientBuilder.clone().build());
            case OPENAI -> openAiEmbedding(openaiBaseUrl, apiKey, openaiEmbeddingModel, openaiEmbeddingDimensions);
            case OPENCODE, ANTHROPIC -> throw new IllegalStateException(provider.label() + " has no embeddings");
        };
        return new RetryingEmbeddingModel(model, retryBaseDelayMs, 8);
    }

    /** Chat model for the provider. Protected so tests can swap in a fake. */
    protected ChatModel buildChatModel(AiProvider provider, String apiKey) {
        return switch (provider) {
            case GEMINI -> new GeminiChatModel(geminiBaseUrl, geminiChatModel, apiKey, geminiKeyHeader);
            case OPENAI -> openAiChat(openaiBaseUrl, apiKey, openaiChatModel);
            case OPENCODE -> openAiChat(opencodeBaseUrl, apiKey, opencodeChatModel);
            case ANTHROPIC -> new ClaudeChatModel(anthropicBaseUrl, anthropicChatModel, apiKey, 2048);
        };
    }

    private OpenAIClient client(String baseUrl, String apiKey) {
        return new OpenAIClientImpl(ClientOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .httpClient(httpClient)
                .build());
    }

    private ChatModel openAiChat(String baseUrl, String apiKey, String model) {
        return OpenAiChatModel.builder()
                .openAiClient(client(baseUrl, apiKey))
                .options(OpenAiChatOptions.builder().model(model).apiKey(apiKey).baseUrl(baseUrl).build())
                .build();
    }

    private EmbeddingModel openAiEmbedding(String baseUrl, String apiKey, String model, int dimensions) {
        OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder()
                .model(model).apiKey(apiKey).baseUrl(baseUrl).dimensions(dimensions);
        return OpenAiEmbeddingModel.builder()
                .openAiClient(client(baseUrl, apiKey))
                .options(options.build())
                .build();
    }

    private VectorStore buildVectorStore(AiProvider provider, String apiKey) {
        // The table is created once; stores built later for visitor keys skip the DDL.
        boolean firstForProvider = schemaReady.add(provider);
        PgVectorStore store = PgVectorStore.builder(jdbcTemplate, embeddingModel(provider, apiKey))
                .schemaName("public")
                .vectorTableName(provider.tableName())
                .initializeSchema(firstForProvider)
                .dimensions(dimensions(provider))
                .build();
        // Manually built stores are not Spring beans, so trigger schema init explicitly.
        store.afterPropertiesSet();
        return store;
    }
}
