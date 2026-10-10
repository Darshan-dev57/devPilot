package devPilot.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import devPilot.backend.services.Visitors;
import devPilot.backend.services.ai.AiKeyResolver;
import devPilot.backend.services.ai.AiModelFactory;
import devPilot.backend.services.ai.AiProvider;
import devPilot.backend.services.ai.VisitorKeys;
import devPilot.backend.services.limits.UsageLimiter;
import lombok.RequiredArgsConstructor;

/** Which model answers this visitor and how much of today's free use they have left. */
@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageLimiter usageLimiter;
    private final AiKeyResolver aiKeyResolver;
    private final AiModelFactory aiModelFactory;

    @GetMapping
    public UsageResponse usage(
            @RequestHeader(Visitors.HEADER) String visitor,
            @RequestHeader(name = VisitorKeys.GEMINI_HEADER, required = false) String geminiKey,
            @RequestHeader(name = VisitorKeys.CHAT_PROVIDER_HEADER, required = false) String chatProvider,
            @RequestHeader(name = VisitorKeys.CHAT_KEY_HEADER, required = false) String chatKey,
            @RequestHeader(name = VisitorKeys.CHAT_MODEL_HEADER, required = false) String chatModel) {
        UsageLimiter.Usage usage = usageLimiter.usage(Visitors.parse(visitor));
        VisitorKeys keys = new VisitorKeys(geminiKey, chatProvider, chatKey, chatModel);
        boolean ownChat = keys.hasChatKey() || keys.hasGemini();
        var answering = ownChat ? aiKeyResolver.chatKey(keys) : null;
        AiProvider chat = answering != null ? answering.provider() : aiKeyResolver.chatProvider();
        String model = answering != null && answering.model() != null ? answering.model()
                : answering != null ? aiModelFactory.visitorDefaultModel(chat) : aiModelFactory.chatModelName(chat);
        return new UsageResponse(chat.label(), model, aiKeyResolver.configured(),
                usage.questionsLeft(), usage.questionsPerDay(), usage.indexesLeft(), usage.indexesPerDay(),
                keys.hasGemini(), ownChat);
    }

    /**
     * ownGeminiKey: indexing runs on the visitor's Gemini key (no index limit).
     * ownChatKey: answers run on the visitor's own key (no question limit).
     */
    public record UsageResponse(String provider, String model, boolean configured, int questionsLeft,
            int questionsPerDay, int indexesLeft, int indexesPerDay, boolean ownGeminiKey, boolean ownChatKey) {
    }
}
