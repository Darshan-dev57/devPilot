package devPilot.backend.services.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A visitor's own key for OpenAI, Claude or OpenCode Zen. There are no such keys on the
 * build machine, so the real clients run against a local fake of each API, using the
 * wire formats from the providers' docs, including the errors they send.
 */
class VisitorChatProvidersTest {

    HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();
    /** path -> canned error (status, body); absent means stream a normal answer. */
    final Map<String, Object[]> errors = new ConcurrentHashMap<>();
    AiModelFactory factory;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        factory = new AiModelFactory(null, RestClient.builder());
        ReflectionTestUtils.setField(factory, "opencodeBaseUrl", base + "/zen/v1");
        ReflectionTestUtils.setField(factory, "opencodeChatModel", "deepseek-v4.1-flash");
        ReflectionTestUtils.setField(factory, "openaiBaseUrl", base + "/openai/v1");
        ReflectionTestUtils.setField(factory, "openaiVisitorChatModel", "gpt-6-luna");
        ReflectionTestUtils.setField(factory, "anthropicBaseUrl", base + "/anthropic");
        ReflectionTestUtils.setField(factory, "anthropicChatModel", "claude-sonnet-5-5");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String apiKey = exchange.getRequestHeaders().getFirst("x-api-key");
        requests.add(path + " " + (auth != null ? auth : "x-api-key " + apiKey) + " "
                + exchange.getRequestHeaders().getFirst("anthropic-version") + " " + body);
        Object[] error = errors.get(path);
        if (error != null) {
            byte[] bytes = ((String) error[1]).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders((Integer) error[0], bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String piece : List.of("Owners are", " validated in", " OwnerController.")) {
                String event = path.startsWith("/anthropic")
                        ? "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + piece + "\"}}\n\n"
                        : "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + piece
                                + "\"},\"finish_reason\":null}]}\n\n";
                out.write(event.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.write((path.startsWith("/anthropic")
                    ? "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                    : "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    static String streamed(ChatModel model) {
        return String.join("", model.stream(new Prompt("where are owners validated?"))
                .map(r -> r.getResult() == null || r.getResult().getOutput().getText() == null ? "" : r.getResult().getOutput().getText())
                .collectList().block());
    }

    static AiKeyResolver.UserAiKey visitor(AiProvider provider, String key, String model) {
        return new AiKeyResolver.UserAiKey(provider, key, true, model);
    }

    static Throwable failure(ChatModel model) {
        try {
            streamed(model);
            return null;
        } catch (RuntimeException ex) {
            return ex;
        }
    }

    @Test
    void openCodeStreamsWithTheVisitorsKeyAndDefaultModel() {
        assertThat(streamed(factory.chatModel(visitor(AiProvider.OPENCODE, "zen-test-key", null))))
                .isEqualTo("Owners are validated in OwnerController.");
        assertThat(requests.get(0)).startsWith("/zen/v1/chat/completions Bearer zen-test-key ")
                .contains("\"model\":\"deepseek-v4.1-flash\"");
    }

    @Test
    void openAiStreamsWithTheModelTheVisitorPicked() {
        assertThat(streamed(factory.chatModel(visitor(AiProvider.OPENAI, "sk-visitor", "gpt-6.1-sol"))))
                .isEqualTo("Owners are validated in OwnerController.");
        assertThat(requests.get(0)).startsWith("/openai/v1/chat/completions Bearer sk-visitor ")
                .contains("\"model\":\"gpt-6.1-sol\"");
    }

    @Test
    void claudeStreamsFromTheMessagesApi() {
        assertThat(streamed(factory.chatModel(visitor(AiProvider.ANTHROPIC, "sk-ant-visitor", null))))
                .isEqualTo("Owners are validated in OwnerController.");
        assertThat(requests.get(0)).startsWith("/anthropic/v1/messages x-api-key sk-ant-visitor 2023-06-01 ")
                .contains("\"model\":\"claude-sonnet-5-5\"").contains("\"stream\":true").contains("\"max_tokens\":2048");
    }

    @Test
    void eachProvidersRefusalsBecomeAPlainSentence() {
        errors.put("/zen/v1/chat/completions", new Object[] { 403,
                "{\"type\":\"error\",\"error\":{\"type\":\"FreeTierError\",\"message\":\"OpenCode's free tier can only be used from within OpenCode\"}}" });
        errors.put("/anthropic/v1/messages", new Object[] { 401,
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}" });
        errors.put("/openai/v1/chat/completions", new Object[] { 429,
                "{\"error\":{\"message\":\"You exceeded your current quota\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}" });

        assertThat(AiErrors.describe(failure(factory.chatModel(visitor(AiProvider.OPENCODE, "k", "longcat-free"))), AiProvider.OPENCODE, true))
                .startsWith("OpenCode Zen refused: its free models only work inside the OpenCode app.");
        assertThat(AiErrors.describe(failure(factory.chatModel(visitor(AiProvider.ANTHROPIC, "bad", null))), AiProvider.ANTHROPIC, true))
                .isEqualTo("Your Claude key was rejected. Check it in \"Use your own key\".");
        assertThat(AiErrors.describe(failure(factory.chatModel(visitor(AiProvider.OPENAI, "sk-empty", null))), AiProvider.OPENAI, true))
                .isEqualTo("Your OpenAI account has no credit left. Top it up or remove your key to use the free allowance.");

        errors.put("/anthropic/v1/messages", new Object[] { 400,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Your credit balance is too low to access the Anthropic API.\"}}" });
        assertThat(AiErrors.describe(failure(factory.chatModel(visitor(AiProvider.ANTHROPIC, "k", null))), AiProvider.ANTHROPIC, true))
                .isEqualTo("Your Claude account has no credit left. Top it up or remove your key to use the free allowance.");
    }
}
