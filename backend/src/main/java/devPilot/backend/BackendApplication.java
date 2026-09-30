package devPilot.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * No Spring AI auto-configuration exclusions are needed here: the pom depends on the
 * plain spring-ai-openai / spring-ai-pgvector-store jars rather than the starters, so
 * no auto-configuration attempts to build a server-key-backed model at startup.
 * See the comment in pom.xml and AiModelFactory.
 */
@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
