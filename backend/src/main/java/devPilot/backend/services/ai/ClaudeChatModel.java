package devPilot.backend.services.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claude through Anthropic's Messages API with streaming ({@code "stream": true}), for a
 * visitor's own key. Text arrives in {@code content_block_delta} events.
 */
public class ClaudeChatModel implements ChatModel {

    static final String API_VERSION = "2023-06-01";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final int maxTokens;

    public ClaudeChatModel(String baseUrl, String model, String apiKey, int maxTokens) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String text = String.join("", stream(prompt)
                .map(r -> r.getResult() == null ? "" : r.getResult().getOutput().getText())
                .collectList()
                .block());
        return response(text);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.<ChatResponse>create(sink -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                        .timeout(Duration.ofMinutes(3))
                        .header("Content-Type", "application/json")
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", API_VERSION)
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body(prompt))))
                        .build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    String error;
                    try (InputStream in = response.body()) {
                        error = new String(in.readNBytes(4000), StandardCharsets.UTF_8);
                    }
                    sink.error(httpError(response.statusCode(), error));
                    return;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!sink.isCancelled() && (line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        JsonNode event = JSON.readTree(line.substring(5).trim());
                        String type = event.path("type").asString();
                        if ("error".equals(type)) {
                            sink.error(new IllegalStateException("Claude stream error: "
                                    + event.path("error").path("type").asString() + " "
                                    + event.path("error").path("message").asString()));
                            return;
                        }
                        if ("content_block_delta".equals(type) && "text_delta".equals(event.path("delta").path("type").asString())) {
                            String text = event.path("delta").path("text").asString();
                            if (!text.isEmpty()) {
                                sink.next(response(text));
                            }
                        }
                    }
                }
                sink.complete();
            } catch (IOException ex) {
                sink.error(ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                sink.error(ex);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static RuntimeException httpError(int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return status >= 500
                ? HttpServerErrorException.create(HttpStatusCode.valueOf(status), "Claude error", null, bytes, StandardCharsets.UTF_8)
                : HttpClientErrorException.create(HttpStatusCode.valueOf(status), "Claude error", null, bytes, StandardCharsets.UTF_8);
    }

    private Map<String, Object> body(Prompt prompt) {
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            if (message.getMessageType() == MessageType.SYSTEM) {
                system.append(message.getText()).append('\n');
            } else {
                String role = message.getMessageType() == MessageType.ASSISTANT ? "assistant" : "user";
                messages.add(Map.of("role", role, "content", message.getText()));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        if (!system.isEmpty()) {
            body.put("system", system.toString());
        }
        body.put("messages", messages);
        return body;
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder().model(model).build();
    }
}
