package devPilot.backend.services.ai;

import java.util.Map;
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
 * Builds the chat model, embedding model and pgvector store for the server's AI provider.
 *
 * <ul>
 *   <li>Gemini (default): Google's native REST API with the server's GEMINI_API_KEY.</li>
 *   <li>OpenAI: Spring AI's OpenAI client with OPENAI_API_KEY.</li>
 *   <li>Ollama (local, optional): Ollama's OpenAI-compatible API at OLLAMA_URL, no key.</li>
 * </ul>
 * Models are cached per provider and key; there is one key per server.
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

    @Value("${app.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;
    @Value("${app.ai.ollama.chat-model:qwen2.5-coder:1.5b}")
    private String ollamaChatModel;
    @Value("${app.ai.ollama.embedding-model:nomic-embed-text}")
    private String ollamaEmbeddingModel;
    @Value("${app.ai.ollama.embedding-dimensions:768}")
    private int ollamaEmbeddingDimensions;

    @Value("${app.ai.retry-base-delay-ms:2000}")
    private long retryBaseDelayMs;

    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, VectorStore> vectorStores = new ConcurrentHashMap<>();
    private final HttpClient httpClient = SpringAiOpenAiHttpClient.builder().build();

    public AiModelFactory(JdbcTemplate jdbcTemplate, RestClient.Builder restClientBuilder) {
        this.jdbcTemplate = jdbcTemplate;
        this.restClientBuilder = restClientBuilder;
    }

    public ChatModel chatModel(AiProvider provider, String apiKey) {
        return chatModels.computeIfAbsent(provider.name(), k -> buildChatModel(provider, apiKey));
    }

    public VectorStore vectorStore(AiProvider provider, String apiKey) {
        return vectorStores.computeIfAbsent(provider.name(), k -> buildVectorStore(provider, apiKey));
    }

    /**
     * One tiny embedding call that proves the AI provider answers before an index job
     * starts; otherwise a broken key would only fail after old vectors were cleared.
     */
    public void verifyKey(AiProvider provider, String apiKey) {
        try {
            embeddingModel(provider, apiKey).embed("ping");
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException(AiErrors.describe(ex, provider));
        }
    }

    public String chatModelName(AiProvider provider) {
        return switch (provider) {
            case GEMINI -> geminiChatModel;
            case OPENAI -> openaiChatModel;
            case OLLAMA -> ollamaChatModel;
        };
    }

    public String tableName(AiProvider provider) {
        return provider.tableName();
    }

    protected int dimensions(AiProvider provider) {
        return switch (provider) {
            case GEMINI -> geminiEmbeddingDimensions;
            case OPENAI -> openaiEmbeddingDimensions;
            case OLLAMA -> ollamaEmbeddingDimensions;
        };
    }

    /** Embedding model for the provider. Protected so tests can swap in a fake. */
    protected EmbeddingModel embeddingModel(AiProvider provider, String apiKey) {
        EmbeddingModel model = switch (provider) {
            case GEMINI -> new GeminiEmbeddingModel(apiKey, geminiBaseUrl, geminiKeyHeader, geminiEmbeddingModel,
                    geminiEmbeddingDimensions, restClientBuilder.clone().build());
            case OPENAI -> openAiEmbedding(openaiBaseUrl, apiKey, openaiEmbeddingModel, openaiEmbeddingDimensions, true);
            case OLLAMA -> openAiEmbedding(ollamaBaseUrl + "/v1", "ollama", ollamaEmbeddingModel,
                    ollamaEmbeddingDimensions, false);
        };
        return new RetryingEmbeddingModel(model, retryBaseDelayMs, 8);
    }

    /** Chat model for the provider. Protected so tests can swap in a fake. */
    protected ChatModel buildChatModel(AiProvider provider, String apiKey) {
        return switch (provider) {
            case GEMINI -> new GeminiChatModel(geminiBaseUrl, geminiChatModel, apiKey, geminiKeyHeader);
            case OPENAI -> openAiChat(openaiBaseUrl, apiKey, openaiChatModel);
            case OLLAMA -> openAiChat(ollamaBaseUrl + "/v1", "ollama", ollamaChatModel);
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

    private EmbeddingModel openAiEmbedding(String baseUrl, String apiKey, String model, int dimensions,
            boolean sendDimensions) {
        OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder()
                .model(model).apiKey(apiKey).baseUrl(baseUrl);
        if (sendDimensions) {
            options.dimensions(dimensions);
        }
        return OpenAiEmbeddingModel.builder()
                .openAiClient(client(baseUrl, apiKey))
                .options(options.build())
                .build();
    }

    private VectorStore buildVectorStore(AiProvider provider, String apiKey) {
        PgVectorStore store = PgVectorStore.builder(jdbcTemplate, embeddingModel(provider, apiKey))
                .schemaName("public")
                .vectorTableName(provider.tableName())
                .initializeSchema(true)
                .dimensions(dimensions(provider))
                .build();
        // Manually built stores are not Spring beans, so trigger schema init explicitly.
        store.afterPropertiesSet();
        return store;
    }
}
