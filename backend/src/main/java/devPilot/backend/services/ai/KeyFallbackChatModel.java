package devPilot.backend.services.ai;

import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/** Answers on the server's Gemini keys, moving to the next key when one is stopped. */
public class KeyFallbackChatModel implements ChatModel {

    private final GeminiKeyPool pool;
    private final List<ChatModel> models;

    public KeyFallbackChatModel(GeminiKeyPool pool, Function<String, ChatModel> perKey) {
        this.pool = pool;
        this.models = IntStream.range(0, pool.size()).mapToObj(i -> perKey.apply(pool.key(i))).toList();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return pool.call(i -> models.get(i).call(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return pool.stream(i -> models.get(i).stream(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return models.get(0).getDefaultOptions();
    }
}
