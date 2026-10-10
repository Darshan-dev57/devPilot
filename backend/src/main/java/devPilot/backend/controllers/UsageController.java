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
            @RequestHeader(name = VisitorKeys.OPENCODE_HEADER, required = false) String opencodeKey,
            @RequestHeader(name = VisitorKeys.OPENCODE_MODEL_HEADER, required = false) String opencodeModel) {
        UsageLimiter.Usage usage = usageLimiter.usage(Visitors.parse(visitor));
        VisitorKeys keys = new VisitorKeys(geminiKey, opencodeKey, opencodeModel);
        AiProvider chat = keys.hasOpencode() ? AiProvider.OPENCODE : keys.hasGemini() ? AiProvider.GEMINI : aiKeyResolver.chatProvider();
        String model = keys.hasOpencode() && !keys.opencodeModel().isEmpty() ? keys.opencodeModel() : aiModelFactory.chatModelName(chat);
        return new UsageResponse(chat.label(), model, aiKeyResolver.configured(),
                usage.questionsLeft(), usage.questionsPerDay(), usage.indexesLeft(), usage.indexesPerDay(),
                keys.hasGemini(), keys.hasOpencode());
    }

    /** ownGeminiKey / ownOpencodeKey: the visitor's own key is in use, so the limits do not apply to it. */
    public record UsageResponse(String provider, String model, boolean configured, int questionsLeft,
            int questionsPerDay, int indexesLeft, int indexesPerDay, boolean ownGeminiKey, boolean ownOpencodeKey) {
    }
}
