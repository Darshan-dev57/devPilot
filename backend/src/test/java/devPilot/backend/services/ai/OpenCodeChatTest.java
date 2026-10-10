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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;


/**
 * OpenCode Zen speaks the OpenAI chat-completions API. There is no OpenCode key on the
 * build machine, so this runs the real client against a local fake of that API, with a
 * visitor's key as DevPilot sends it.
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
        ReflectionTestUtils.setField(factory, "opencodeChatModel", "deepseek-v4.1-flash");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
        if (modelWithdrawn) {
            // What Zen answered on 2026-10-10 for a free model called from outside its app.
            byte[] error = "{\"type\":\"error\",\"error\":{\"type\":\"FreeTierError\",\"message\":\"OpenCode's free tier can only be used from within OpenCode\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(403, error.length);
            exchange.getResponseBody().write(error);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String piece : List.of("Owners are", " validated in", " OwnerController.")) {
                out.write(("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-v4.1-flash\","
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
        ChatModel model = factory.chatModel(new AiKeyResolver.UserAiKey(AiProvider.OPENCODE, "zen-test-key", true, null));
        assertThat(streamed(model)).isEqualTo("Owners are validated in OwnerController.");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0)).startsWith("Bearer zen-test-key ").contains("\"model\":\"deepseek-v4.1-flash\"");
    }

    @Test
    void aRefusedFreeModelGivesTheVisitorAClearMessage() {
        modelWithdrawn = true;
        Throwable error = null;
        try {
            streamed(factory.chatModel(new AiKeyResolver.UserAiKey(AiProvider.OPENCODE, "zen-test-key", true, null)));
        } catch (RuntimeException ex) {
            error = ex;
        }
        assertThat(error).isNotNull();
        assertThat(AiErrors.describe(error, AiProvider.OPENCODE, true))
                .startsWith("OpenCode Zen refused: its free models only work inside the OpenCode app.");
    }
}
