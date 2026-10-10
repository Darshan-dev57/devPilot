package devPilot.backend.services.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Flux;

/**
 * OpenCode Zen speaks the OpenAI chat-completions API. There is no OpenCode key on the
 * build machine, so this runs the real client against a local fake of that API.
 */
class OpenCodeChatTest {

    HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile boolean modelWithdrawn;
    AiModelFactory factory;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/zen/v1/chat/completions", this::handle);
        server.start();
        factory = new AiModelFactory(null, RestClient.builder());
        ReflectionTestUtils.setField(factory, "opencodeBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/zen/v1");
        ReflectionTestUtils.setField(factory, "opencodeChatModel", "longcat-2.5-preview-free");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
        if (modelWithdrawn) {
            byte[] error = "{\"error\":{\"message\":\"Model longcat-2.5-preview-free not found\",\"type\":\"invalid_request_error\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, error.length);
            exchange.getResponseBody().write(error);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String piece : List.of("Owners are", " validated in", " OwnerController.")) {
                out.write(("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"longcat-2.5-preview-free\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + piece
                        + "\"},\"finish_reason\":null}]}\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    static String streamed(ChatModel model) {
        return String.join("", model.stream(new Prompt("where are owners validated?"))
                .map(r -> r.getResult() == null || r.getResult().getOutput().getText() == null ? "" : r.getResult().getOutput().getText())
                .collectList().block());
    }

    @Test
    void streamsFromTheOpenAiCompatibleEndpointWithTheZenKeyAndModel() {
        ChatModel model = factory.chatModel(AiProvider.OPENCODE, "zen-test-key");
        assertThat(streamed(model)).isEqualTo("Owners are validated in OwnerController.");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0)).startsWith("Bearer zen-test-key ").contains("\"model\":\"longcat-2.5-preview-free\"");
    }

    @Test
    void aWithdrawnFreeModelFallsBackToTheEmbeddingProvidersChatModel() {
        modelWithdrawn = true;
        ChatModel gemini = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("answered by Gemini"))));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }
        };
        ChatModel model = new FallbackChatModel(factory.chatModel(AiProvider.OPENCODE, "zen-test-key"), gemini, "OpenCode Zen");
        assertThat(streamed(model)).isEqualTo("answered by Gemini");
    }

    @Test
    void withoutAFallbackTheVisitorGetsAClearMessage() {
        modelWithdrawn = true;
        Throwable error = null;
        try {
            streamed(factory.chatModel(AiProvider.OPENCODE, "zen-test-key"));
        } catch (RuntimeException ex) {
            error = ex;
        }
        assertThat(error).isNotNull();
        assertThat(AiErrors.describe(error, AiProvider.OPENCODE))
                .startsWith("The free OpenCode Zen model is no longer available");
    }
}
