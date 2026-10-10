package devPilot.backend.services.ai;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Answers with the primary model and switches to a second one if the primary fails before
 * it wrote a single word. OpenCode Zen's free models are offered "for a limited time", so
 * the one in OPENCODE_MODEL can disappear; visitors then still get an answer from Gemini
 * and the server log says what happened.
 */
@Slf4j
public class FallbackChatModel implements ChatModel {

    private final ChatModel primary;
    private final ChatModel fallback;
    private final String primaryName;

    public FallbackChatModel(ChatModel primary, ChatModel fallback, String primaryName) {
        this.primary = primary;
        this.fallback = fallback;
        this.primaryName = primaryName;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            return primary.call(prompt);
        } catch (RuntimeException ex) {
            log.warn("{} failed ({}); answering with the fallback model", primaryName, ex.toString());
            return fallback.call(prompt);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        AtomicBoolean started = new AtomicBoolean();
        return primary.stream(prompt)
                .doOnNext(r -> started.set(true))
                .onErrorResume(err -> !started.get(), err -> {
                    log.warn("{} failed before answering ({}); answering with the fallback model", primaryName, err.toString());
                    return fallback.stream(prompt);
                });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return primary.getDefaultOptions();
    }
}
