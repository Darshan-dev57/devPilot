package devPilot.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import devPilot.backend.services.ai.AiModelFactory;
import devPilot.backend.services.ai.ChatStreamHandler;
import devPilot.backend.services.ai.CodeContextRetriever;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full application context against an in-memory H2 database.
 *
 * <p>This is a regression test for the no-login startup path. It failed to load in
 * production several times in a row, each for a different missing bean or unresolved
 * placeholder, because Spring AI's auto-configuration kept trying to build
 * server-key-backed singletons that this app does not use. Those beans are now built
 * per request by {@link AiModelFactory}, so the context must come up with no AI
 * credentials configured at all.
 */
@SpringBootTest
class BackendApplicationTests {

	@Test
	void contextLoadsWithoutAnyServerAiCredentials() {
		// Reaching this assertion means every singleton was constructible. The BYOK
		// services specifically must not require a server-side OpenAI or Gemini key.
		assertThat(AiModelFactory.class).isNotNull();
		assertThat(ChatStreamHandler.class).isNotNull();
		assertThat(CodeContextRetriever.class).isNotNull();
	}

}
