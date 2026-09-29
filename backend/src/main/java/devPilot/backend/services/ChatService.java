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
import devPilot.backend.services.ai.AiKeyResolver;
import devPilot.backend.services.ai.AiModelFactory;
import devPilot.backend.services.ai.ChatPromptBuilder;
import devPilot.backend.services.ai.ChatStreamHandler;
import devPilot.backend.services.ai.CitationMapper;
import devPilot.backend.services.ai.CodeContextRetriever;
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

    @Transactional
    public ChatSessionResponse createSession(CreateChatSessionRequest request) {
        Repository repo = repoService.requireOwned(request.repositoryId());
        if (repo.getIndexStatus() != IndexStatus.READY) {
            throw new BadRequestException("Repository must be indexed before chatting");
        }

        String title = request.title() != null && !request.title().isBlank()
                ? request.title()
                : "Chat with " + repo.getFullName();

        ChatSession session = ChatSession.builder()
                .repositoryId(repo.getId())
                .title(title)
                .build();
        session = chatSessionRepository.save(session);
        return toSessionResponse(session);
    }

    @Transactional(readOnly = true)
    public List<ChatSessionResponse> listSessions(UUID repositoryId) {
        repoService.requireOwned(repositoryId);
        return chatSessionRepository
                .findByRepositoryIdOrderByCreatedAtDesc(repositoryId)
                .stream()
                .map(this::toSessionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(UUID sessionId) {
        ChatSession session = requireSession(sessionId);
        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatSession requireSession(UUID sessionId) {
        return chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Chat session not found"));
    }

    public SseEmitter streamReply(UUID sessionId, String userContent, String apiKey) {
        ChatSession session = requireSession(sessionId);
        Repository repo = repoService.requireOwned(session.getRepositoryId());
        if (repo.getIndexStatus() != IndexStatus.READY) {
            throw new BadRequestException("Repository is not ready for chat");
        }

        ChatMessage userMessage = chatMessageRepository.save(ChatMessage.builder()
                .sessionId(session.getId())
                .role(MessageRole.USER)
                .content(userContent)
                .build());

        var userKey = aiKeyResolver.requireKey(apiKey);
        var userVectorStore = aiModelFactory.vectorStore(userKey.provider(), userKey.apiKey());
        var retrievedContext = codeContextRetriever.retrieve(userVectorStore, repo.getId(), userContent);
        if (retrievedContext.citations().isEmpty()
                && "(no matching code chunks found)".equals(retrievedContext.contextText())) {
            try {
                var fallback = codeContextRetriever.retrieve(repo.getId(), userContent);
                if (!fallback.citations().isEmpty()) {
                    retrievedContext = fallback;
                }
            } catch (Exception ex) {
                org.slf4j.LoggerFactory.getLogger(ChatService.class)
                        .warn("Fallback retrieval failed for repo {}: {}", repo.getId(), ex.getMessage());
            }
        }

        String systemPrompt = chatPromptBuilder.systemPrompt(repo.getFullName());
        String userPrompt = chatPromptBuilder.userPrompt(retrievedContext.contextText(), userContent);

        return chatStreamHandler.stream(
                aiModelFactory.chatModel(userKey.provider(), userKey.apiKey()),
                session.getId(),
                toMessageResponse(userMessage),
                retrievedContext.citations(),
                systemPrompt,
                userPrompt);
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
