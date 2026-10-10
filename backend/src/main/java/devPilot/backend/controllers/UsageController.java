package devPilot.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import devPilot.backend.services.Visitors;
import devPilot.backend.services.ai.AiKeyResolver;
import devPilot.backend.services.ai.AiModelFactory;
import devPilot.backend.services.limits.UsageLimiter;
import lombok.RequiredArgsConstructor;

/** What the server runs on and how much of today's free use this visitor has left. */
@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageLimiter usageLimiter;
    private final AiKeyResolver aiKeyResolver;
    private final AiModelFactory aiModelFactory;

    @GetMapping
    public UsageResponse usage(@RequestHeader(Visitors.HEADER) String visitor) {
        UsageLimiter.Usage usage = usageLimiter.usage(Visitors.parse(visitor));
        var provider = aiKeyResolver.chatProvider();
        return new UsageResponse(provider.label(), aiModelFactory.chatModelName(provider), aiKeyResolver.configured(),
                usage.questionsLeft(), usage.questionsPerDay(), usage.indexesLeft(), usage.indexesPerDay());
    }

    public record UsageResponse(String provider, String model, boolean configured, int questionsLeft,
            int questionsPerDay, int indexesLeft, int indexesPerDay) {
    }
}
