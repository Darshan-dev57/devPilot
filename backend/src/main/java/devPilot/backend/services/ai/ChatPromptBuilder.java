package devPilot.backend.services.ai;

import org.springframework.stereotype.Component;

/**
 * Builds the prompts sent to OpenAI.
 *
 * <p>We use two messages:
 * <ul>
 *   <li><b>System</b> — rules for how the assistant should behave</li>
 *   <li><b>User</b> — retrieved code context + the actual question</li>
 * </ul>
 */
@Component
public class ChatPromptBuilder {

    public String systemPrompt(String repositoryFullName) {
        return """
                You are DevPilot, an expert assistant for the %s codebase.
                - If the user greets you (hi, hello, etc.), respond warmly, briefly introduce yourself as DevPilot for this repo, and invite a code question.
                - Otherwise answer using the provided code context. If the context is insufficient, say you are unsure and suggest what file or area to look at.
                Cite file paths and line ranges when relevant.
                Be concise and technical.
                """.formatted(repositoryFullName);
    }

    public String userPrompt(String codeContext, String question) {
        return """
                Code context:
                %s

                User question:
                %s
                """.formatted(codeContext, question);
    }
}
