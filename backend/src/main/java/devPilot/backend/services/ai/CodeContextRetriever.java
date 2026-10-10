package devPilot.backend.services.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import devPilot.backend.dto.CitationDto;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CodeContextRetriever {
    private static final String NO_MATCHES = "(no matching code chunks found)";

    private final CitationMapper citationMapper;

    /**
     * Finds the chunks most similar to the question, scoped to one repository.
     * Stores are built per request in AiModelFactory (each visitor brings their own key).
     */
    public RetrievedContext retrieve(VectorStore store, UUID repositoryId, String question) {
        var filter = new FilterExpressionBuilder()
                .eq(RagSettings.METADATA_REPO_ID, repositoryId.toString())
                .build();

        var search = SearchRequest.builder()
                .query(question)
                .topK(RagSettings.TOP_K_CHUNKS)
                .filterExpression(filter)
                .build();

        List<Document> documents = store.similaritySearch(search);
        return build(documents);
    }

    /** Visible for tests: turns ranked chunks into prompt context and citation chips. */
    RetrievedContext build(List<Document> documents) {
        // One chip per file, best match first, line range widened to cover every hit in it.
        Map<String, CitationDto> byFile = new LinkedHashMap<>();
        for (Document document : documents) {
            CitationDto citation = citationMapper.fromDocument(document);
            if (citation.filePath() == null) {
                continue;
            }
            byFile.merge(citation.filePath(), citation, CodeContextRetriever::widen);
        }
        List<CitationDto> citations = new ArrayList<>(byFile.values());

        StringBuilder context = new StringBuilder();
        for (Document document : documents) {
            if (document.getText() == null || document.getText().isBlank()) {
                continue;
            }
            if (!context.isEmpty()) {
                context.append("\n\n---\n\n");
            }
            context.append(document.getText());
        }
        return new RetrievedContext(citations, context.isEmpty() ? NO_MATCHES : context.toString());
    }

    private static CitationDto widen(CitationDto a, CitationDto b) {
        if (a.startLine() == null || b.startLine() == null) {
            return a;
        }
        return new CitationDto(a.filePath(),
                Math.min(a.startLine(), b.startLine()),
                Math.max(a.endLine() == null ? a.startLine() : a.endLine(),
                        b.endLine() == null ? b.startLine() : b.endLine()),
                a.language());
    }
}
