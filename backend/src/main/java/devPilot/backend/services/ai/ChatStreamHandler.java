package devPilot.backend.services.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import devPilot.backend.dto.ChatMessageResponse;
import devPilot.backend.dto.CitationDto;
import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.MessageRole;
import devPilot.backend.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.Disposable;
import tools.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Generation step: call OpenAI via Spring AI and stream tokens to the browser over SSE.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatStreamHandler {

    private final ChatMessageRepository chatMessageRepository;
    private final CitationMapper citationMapper;
    private final JsonMapper jsonMapper;

    /**
     * Stream using a caller-supplied model, e.g. a per-request BYOK model.
     * No ChatModel field — models are built per-request in AiModelFactory.
     */
    public SseEmitter stream(
            ChatModel model,
            AiProvider provider,
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            List<CitationDto> citations,
            String systemPrompt,
            String userPrompt) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);
        StringBuilder fullReply = new StringBuilder();

        try {
            emitter.send(SseEmitter.event()
                    .name("user_message")
                    .data(savedUserMessage));

            Disposable subscription = ChatClient.builder(model)
                    .build()
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .stream()
                    .content()
                    .subscribe(
                            token -> appendToken(emitter, fullReply, token),
                            err -> {
                                log.warn("Chat stream error: {}", err.toString());
                                sendErrorAndComplete(emitter, AiErrors.describe(err, provider));
                            },
                            () -> completeStream(emitter, sessionId, fullReply, citations));
            // Stop paying for tokens nobody will read when the browser goes away.
            emitter.onCompletion(subscription::dispose);
            emitter.onTimeout(subscription::dispose);
            emitter.onError(err -> subscription.dispose());
        } catch (Exception ex) {
            log.warn("Chat stream setup failed: {}", ex.toString());
            sendErrorAndComplete(emitter, AiErrors.describe(ex, provider));
        }

        return emitter;
    }

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("message", message), MediaType.APPLICATION_JSON));
            // complete(), not completeWithError(): the error is already in the stream as an
            // event, and completeWithError would make Spring try to write a second error body.
            emitter.complete();
        } catch (Exception sendEx) {
            log.debug("Could not send error event to client: {}", sendEx.toString());
        }
    }

    /**
     * Tokens are sent as JSON string literals. Sent raw, a token with a leading space loses it
     * (the SSE parser strips one space after "data:") and a token with a newline is split
     * across data lines, so the client's JSON.parse failed on almost every token.
     */
    private void appendToken(SseEmitter emitter, StringBuilder fullReply, String token) {
        fullReply.append(token);
        try {
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(jsonMapper.writeValueAsString(token)));
        } catch (Exception ex) {
            throw new IllegalStateException("Client disconnected", ex);
        }
    }

    private void completeStream(
            SseEmitter emitter,
            UUID sessionId,
            StringBuilder fullReply,
            List<CitationDto> citations) {
        try {
            String content = fullReply.toString();
            if (content.isBlank()) {
                content = "I couldn't generate a response for that. Try rephrasing — for example, ask what a specific file or feature does, and I'll use the indexed code to answer.";
                fullReply = new StringBuilder(content);
            }
            ChatMessage assistant = chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .role(MessageRole.ASSISTANT)
                    .content(content)
                    .citations(citationMapper.toJson(citations))
                    .build());

            emitter.send(SseEmitter.event()
                    .name("assistant_message")
                    .data(toMessageResponse(assistant)));
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception ex) {
            log.warn("Failed to complete chat stream: {}", ex.toString());
            sendErrorAndComplete(emitter, "Could not save the answer. Please try again.");
        }
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                citationMapper.fromJson(message.getCitations()),
                message.getCreatedAt());
    }
}
