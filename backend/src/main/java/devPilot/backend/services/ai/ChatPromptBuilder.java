package devPilot.backend.services.ai;

import java.util.List;

import org.springframework.stereotype.Component;

import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.MessageRole;

/**
 * Builds the two prompt messages: a system message with the rules, and a user message
 * with the retrieved code, the last few turns of the conversation, and the question.
 */
@Component
public class ChatPromptBuilder {

    /** How many earlier messages are replayed so follow-up questions make sense. */
    public static final int HISTORY_MESSAGES = 6;

    private static final int HISTORY_CHARS_PER_MESSAGE = 1200;

    public String systemPrompt(String repositoryFullName) {
        return """
                You are DevPilot, an assistant for the GitHub repository %s.
                - If the user just greets you, reply briefly, say you can answer questions about this repo, and suggest one.
                - Otherwise answer only from the code context below. Each block starts with "File: <path> (lines a-b)".
                - When you use a block, cite it inline as `path:a-b`.
                - If the context does not contain the answer, say so plainly and name the files or folders most likely to hold it. Do not invent code.
                - Be concise and technical. Use short code snippets when they help.
                """.formatted(repositoryFullName);
    }

    public String userPrompt(String codeContext, List<ChatMessage> history, String question) {
        StringBuilder conversation = new StringBuilder();
        for (ChatMessage message : history) {
            String text = message.getContent();
            if (text.length() > HISTORY_CHARS_PER_MESSAGE) {
                text = text.substring(0, HISTORY_CHARS_PER_MESSAGE) + " ...";
            }
            conversation.append(message.getRole() == MessageRole.USER ? "User: " : "DevPilot: ")
                    .append(text).append("\n");
        }
        return """
                Code context:
                %s

                Conversation so far:
                %s
                User question:
                %s
                """.formatted(codeContext, conversation.isEmpty() ? "(none)\n" : conversation, question);
    }
}
