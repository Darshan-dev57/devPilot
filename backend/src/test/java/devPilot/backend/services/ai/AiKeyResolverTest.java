package devPilot.backend.services.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import devPilot.backend.exceptions.BadRequestException;

/**
 * Provider routing for bring-your-own-key requests.
 *
 * <p>The client states its provider in the X-AI-Provider header, because the backend
 * cannot reliably identify a Gemini key from its format: Google issues Gemini keys
 * under more than one prefix, so anything prefix-based silently sends them to OpenAI.
 */
class AiKeyResolverTest {

    private final AiKeyResolver resolver = new AiKeyResolver();

    @Test
    void honoursTheProviderHeader() {
        // A Gemini key that does not match the AIza prefix would previously be
        // misrouted to OpenAI and fail.
        var resolved = resolver.requireKey("AQ.test-dummy-key", "gemini");

        assertEquals(AiProvider.GEMINI, resolved.provider());
        assertEquals("AQ.test-dummy-key", resolved.apiKey());
    }

    @Test
    void headerIsAuthoritativeEvenForAmbiguousKeys() {
        assertEquals(AiProvider.OPENAI, resolver.requireKey("AIza-test", "openai").provider());
        assertEquals(AiProvider.GEMINI, resolver.requireKey("sk-test", "gemini").provider());
    }

    @Test
    void headerIsCaseInsensitive() {
        assertEquals(AiProvider.GEMINI, resolver.requireKey("k", " Gemini ").provider());
    }

    @Test
    void rejectsUnknownProvider() {
        assertThrows(BadRequestException.class, () -> resolver.requireKey("k", "anthropic"));
    }

    @Test
    void fallsBackToKeyPrefixWhenHeaderAbsent() {
        assertEquals(AiProvider.GEMINI, resolver.requireKey("AIza-test", null).provider());
        assertEquals(AiProvider.GEMINI, resolver.requireKey("AQ.test", null).provider());
        assertEquals(AiProvider.OPENAI, resolver.requireKey("sk-test", null).provider());
        assertEquals(AiProvider.OPENAI, resolver.requireKey("sk-test", "  ").provider());
    }

    @Test
    void rejectsMissingKey() {
        assertThrows(BadRequestException.class, () -> resolver.requireKey(null, "openai"));
        assertThrows(BadRequestException.class, () -> resolver.requireKey("  ", "openai"));
    }
}
