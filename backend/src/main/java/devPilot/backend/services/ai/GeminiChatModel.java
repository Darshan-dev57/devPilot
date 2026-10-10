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
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini chat through Google's native API ({@code streamGenerateContent?alt=sse}) with a
 * plain API key. The native API streams cleanly and needs no OpenAI compatibility layer.
 */
public class GeminiChatModel implements ChatModel {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final String keyHeader;

    public GeminiChatModel(String baseUrl, String model, String apiKey, String keyHeader) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.apiKey = apiKey;
        this.keyHeader = keyHeader;
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
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                                baseUrl + "/v1beta/models/" + model + ":streamGenerateContent?alt=sse"))
                        .timeout(Duration.ofMinutes(3))
                        .header("Content-Type", "application/json")
                        .header(keyHeader, apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body(prompt))))
                        .build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    String error;
                    try (InputStream in = response.body()) {
                        error = new String(in.readNBytes(4000), StandardCharsets.UTF_8);
                    }
                    sink.error(HttpClientErrorException.create(HttpStatus.valueOf(response.statusCode()),
                            "Gemini error", null, error.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
                    return;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!sink.isCancelled() && (line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        String text = textOf(JSON.readTree(line.substring(5).trim()));
                        if (!text.isEmpty()) {
                            sink.next(response(text));
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

    static String textOf(JsonNode chunk) {
        StringBuilder text = new StringBuilder();
        for (JsonNode candidate : chunk.path("candidates")) {
            for (JsonNode part : candidate.path("content").path("parts")) {
                if (part.has("text") && !part.path("thought").asBoolean(false)) {
                    text.append(part.get("text").asString());
                }
            }
        }
        return text.toString();
    }

    private static Map<String, Object> body(Prompt prompt) {
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> contents = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            if (message.getMessageType() == MessageType.SYSTEM) {
                system.append(message.getText()).append('\n');
            } else {
                String role = message.getMessageType() == MessageType.ASSISTANT ? "model" : "user";
                contents.add(Map.of("role", role, "parts", List.of(Map.of("text", message.getText()))));
            }
        }
        return system.isEmpty()
                ? Map.of("contents", contents)
                : Map.of("systemInstruction", Map.of("parts", List.of(Map.of("text", system.toString()))),
                        "contents", contents);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder().model(model).build();
    }
}
