package devPilot.backend.services.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;

import devPilot.backend.entity.ChatMessage;
import devPilot.backend.repository.ChatMessageRepository;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

class ChatStreamHandlerTest {

    /** Fails with a 500 on the first call, then answers: what Gemini did on 2026-10-10. */
    static class FlakyModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            if (calls.incrementAndGet() == 1) {
                return Flux.error(HttpClientErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Gemini error", null,
                        "{\"error\":{\"code\":500,\"status\":\"INTERNAL\"}}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            }
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("It is 30.")))));
        }
    }

    @Test
    void aProviderHiccupBeforeTheFirstWordIsRetriedWithAFreshRequest() {
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        JsonMapper json = JsonMapper.builder().build();
        ChatStreamHandler handler = new ChatStreamHandler(messages, new CitationMapper(json), json);
        ReflectionTestUtils.setField(handler, "rateLimitBackoffMs", 1L);
        FlakyModel model = new FlakyModel();

        handler.stream(model, AiProvider.GEMINI, false, UUID.randomUUID(), null, List.of(), "system", "question");

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages, timeout(5000)).save(saved.capture());
        assertThat(saved.getValue().getContent()).isEqualTo("It is 30.");
        assertThat(model.calls.get()).isEqualTo(2);
    }
}
