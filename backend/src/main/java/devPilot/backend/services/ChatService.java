package devPilot.backend.services;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import devPilot.backend.dto.ChatMessageResponse;
import devPilot.backend.dto.ChatSessionResponse;
import devPilot.backend.dto.CreateChatSessionRequest;
import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.ChatSession;
import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.MessageRole;
import devPilot.backend.entity.Repository;
import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.repository.ChatMessageRepository;
import devPilot.backend.repository.ChatSessionRepository;
import devPilot.backend.services.ai.AiErrors;
import devPilot.backend.services.ai.AiKeyResolver;
import devPilot.backend.services.ai.RetrievedContext;
import devPilot.backend.services.ai.AiModelFactory;
import devPilot.backend.services.ai.ChatPromptBuilder;
import devPilot.backend.services.ai.ChatStreamHandler;
import devPilot.backend.services.ai.CitationMapper;
import devPilot.backend.services.ai.CodeContextRetriever;
import devPilot.backend.exceptions.TooManyRequestsException;
import devPilot.backend.services.limits.UsageLimiter;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RepoService repoService;
    private final CodeContextRetriever codeContextRetriever;
    private final ChatPromptBuilder chatPromptBuilder;
    private final ChatStreamHandler chatStreamHandler;
    private final CitationMapper citationMapper;
    private final AiKeyResolver aiKeyResolver;
    private final AiModelFactory aiModelFactory;
    private final UsageLimiter usageLimiter;

    static final int MAX_QUESTION_CHARS = 4000;

    @Transactional
    public ChatSessionResponse createSession(CreateChatSessionRequest request, UUID visitorId) {
        Repository repo = repoService.requireVisible(request.repositoryId(), visitorId);
        if (repo.getIndexStatus() != IndexStatus.READY) {
            throw new BadRequestException("Repository must be indexed before chatting");
        }

        String title = request.title() != null && !request.title().isBlank()
                ? request.title()
                : "Chat with " + repo.getFullName();

        ChatSession session = ChatSession.builder()
                .repositoryId(repo.getId())
                .visitorId(visitorId)
                .title(title)
                .build();
        session = chatSessionRepository.save(session);
        return toSessionResponse(session);
    }

    @Transactional(readOnly = true)
    public List<ChatSessionResponse> listSessions(UUID repositoryId, UUID visitorId) {
        repoService.requireVisible(repositoryId, visitorId);
        return chatSessionRepository
                .findByRepositoryIdAndVisitorIdOrderByCreatedAtDesc(repositoryId, visitorId)
                .stream()
                .map(this::toSessionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(UUID sessionId, UUID visitorId) {
        ChatSession session = requireSession(sessionId, visitorId);
        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatSession requireSession(UUID sessionId, UUID visitorId) {
        return chatSessionRepository.findById(sessionId)
                .filter(session -> visitorId.equals(session.getVisitorId()))
                .orElseThrow(() -> new NotFoundException("Chat session not found"));
    }

    public SseEmitter streamReply(UUID sessionId, UUID visitorId, String userContent) {
        ChatSession session = requireSession(sessionId, visitorId);
        Repository repo = repoService.requireVisible(session.getRepositoryId(), visitorId);
        if (repo.getIndexStatus() != IndexStatus.READY) {
            throw new BadRequestException("Repository is not ready for chat");
        }

        if (userContent.length() > MAX_QUESTION_CHARS) {
            throw new BadRequestException("That question is too long. Keep it under " + MAX_QUESTION_CHARS + " characters.");
        }
        var userKey = aiKeyResolver.requireKey();
        // Vectors from one provider's embedding model cannot be searched with another's.
        if (repo.getEmbeddingProvider() != null && !repo.getEmbeddingProvider().equals(userKey.provider().name())) {
            throw new BadRequestException("This repo was indexed with " + label(repo.getEmbeddingProvider())
                    + ", but the server now uses " + label(userKey.provider().name()) + ". Re-index it to keep chatting.");
        }
        usageLimiter.consume(visitorId, UsageLimiter.Kind.QUESTION);

        List<ChatMessage> history = recentHistory(session.getId());
        ChatMessage userMessage = chatMessageRepository.save(ChatMessage.builder()
                .sessionId(session.getId())
                .role(MessageRole.USER)
                .content(userContent)
                .build());

        RetrievedContext retrievedContext;
        try {
            var userVectorStore = aiModelFactory.vectorStore(userKey.provider(), userKey.apiKey());
            retrievedContext = codeContextRetriever.retrieve(userVectorStore, repo.getId(), userContent);
        } catch (RuntimeException ex) {
            chatMessageRepository.delete(userMessage);
            String message = AiErrors.describe(ex, userKey.provider());
            if (AiErrors.isRateLimit(ex)) {
                throw new TooManyRequestsException(message);
            }
            throw new BadRequestException(message);
        }

        String systemPrompt = chatPromptBuilder.systemPrompt(repo.getFullName());
        String userPrompt = chatPromptBuilder.userPrompt(retrievedContext.contextText(), history, userContent);

        return chatStreamHandler.stream(
                aiModelFactory.chatModel(userKey.provider(), userKey.apiKey()),
                userKey.provider(),
                session.getId(),
                toMessageResponse(userMessage),
                retrievedContext.citations(),
                systemPrompt,
                userPrompt);
    }

    private List<ChatMessage> recentHistory(UUID sessionId) {
        List<ChatMessage> all = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return all.subList(Math.max(0, all.size() - ChatPromptBuilder.HISTORY_MESSAGES), all.size());
    }

    private static String label(String provider) {
        return "GEMINI".equals(provider) ? "Gemini" : "OpenAI";
    }

    private ChatSessionResponse toSessionResponse(ChatSession session) {
        return new ChatSessionResponse(
                session.getId(),
                session.getRepositoryId(),
                session.getTitle(),
                session.getCreatedAt());
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                citationMapper.fromJson(message.getCitations()),
                message.getCreatedAt());
    }
}
