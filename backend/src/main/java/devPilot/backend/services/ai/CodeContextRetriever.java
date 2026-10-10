package devPilot.backend.services.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final int PREVIOUS_QUESTION_CHARS = 1000;

    /** Hits in one file at most this many lines apart are shown as one chip. */
    private static final int NEARBY_LINES = 20;

    private final CitationMapper citationMapper;

    /**
     * Finds the chunks most similar to the question, scoped to one repository.
     * Stores are built per request in AiModelFactory (each visitor brings their own key).
     */
    public RetrievedContext retrieve(VectorStore store, UUID repositoryId, String question) {
        return retrieve(store, repositoryId, question, null);
    }

    /**
     * Same, for a question asked after an earlier one in the same chat. A follow-up such as
     * "and where is it registered?" names nothing to search for, so a second search runs on
     * the earlier question plus this one. Most slots still go to hits for this question alone,
     * so a change of topic keeps its own context.
     */
    public RetrievedContext retrieve(VectorStore store, UUID repositoryId, String question, String previousQuestion) {
        List<Document> direct = search(store, repositoryId, question);
        if (previousQuestion == null || previousQuestion.isBlank()) {
            return build(direct);
        }
        String combined = clip(previousQuestion) + "\n" + question;
        return build(merge(direct, search(store, repositoryId, combined)));
    }

    private List<Document> search(VectorStore store, UUID repositoryId, String query) {
        var filter = new FilterExpressionBuilder()
                .eq(RagSettings.METADATA_REPO_ID, repositoryId.toString())
                .build();

        var search = SearchRequest.builder()
                .query(query)
                .topK(RagSettings.TOP_K_CHUNKS)
                .filterExpression(filter)
                .build();

        return store.similaritySearch(search);
    }

    private static String clip(String text) {
        return text.length() <= PREVIOUS_QUESTION_CHARS ? text : text.substring(0, PREVIOUS_QUESTION_CHARS);
    }

    /** Visible for tests: the top direct hits first, then follow-up hits, then the rest, without repeats. */
    static List<Document> merge(List<Document> direct, List<Document> followUp) {
        int directFirst = RagSettings.TOP_K_CHUNKS - RagSettings.TOP_K_CHUNKS / 3;
        Map<String, Document> merged = new LinkedHashMap<>();
        direct.stream().limit(directFirst).forEach(d -> merged.putIfAbsent(d.getId(), d));
        for (Document d : followUp) {
            if (merged.size() >= RagSettings.TOP_K_CHUNKS) {
                break;
            }
            merged.putIfAbsent(d.getId(), d);
        }
        for (Document d : direct) {
            if (merged.size() >= RagSettings.TOP_K_CHUNKS) {
                break;
            }
            merged.putIfAbsent(d.getId(), d);
        }
        return new ArrayList<>(merged.values());
    }

    /** Visible for tests: turns ranked chunks into prompt context and citation chips. */
    RetrievedContext build(List<Document> documents) {
        // Best match first. Hits that sit close together in one file share a chip; hits far
        // apart keep their own, so a big file never turns into one chip spanning the whole file.
        List<CitationDto> citations = new ArrayList<>();
        for (Document document : documents) {
            CitationDto citation = citationMapper.fromDocument(document);
            if (citation.filePath() == null) {
                continue;
            }
            int near = indexOfNearby(citations, citation);
            if (near >= 0) {
                citations.set(near, widen(citations.get(near), citation));
            } else {
                citations.add(citation);
            }
        }

        StringBuilder context = new StringBuilder();
        for (Document document : documents) {
            if (document.getText() == null || document.getText().isBlank()) {
                continue;
            }
            if (!context.isEmpty()) {
                context.append("\n\n---\n\n");
            }
            context.append(numberLines(document.getText()));
        }
        return new RetrievedContext(citations, context.isEmpty() ? NO_MATCHES : context.toString());
    }

    private static final Pattern HEADER = Pattern.compile("\\AFile: [^\\n]* \\(lines (\\d+)-\\d+\\)\\n(?:In: [^\\n]*\\n)?\\n");

    /**
     * Prefixes each code line of a chunk with its line number in the file ("36| ..."), so
     * the model can cite the exact lines instead of the whole chunk. Chunks without the
     * indexer's header are left as they are.
     */
    static String numberLines(String text) {
        Matcher header = HEADER.matcher(text);
        if (!header.find()) {
            return text;
        }
        int line = Integer.parseInt(header.group(1));
        StringBuilder out = new StringBuilder(text.length() + 256).append(header.group());
        String[] lines = text.substring(header.end()).split("\\n", -1);
        int count = lines.length;
        if (count > 0 && lines[count - 1].isEmpty()) {
            count--;
        }
        for (int i = 0; i < count; i++) {
            out.append(line + i).append("| ").append(lines[i]).append('\n');
        }
        return out.toString();
    }

    private static int indexOfNearby(List<CitationDto> citations, CitationDto hit) {
        for (int i = 0; i < citations.size(); i++) {
            CitationDto chip = citations.get(i);
            if (!chip.filePath().equals(hit.filePath())) {
                continue;
            }
            if (chip.startLine() == null || hit.startLine() == null) {
                return i;
            }
            int chipEnd = chip.endLine() == null ? chip.startLine() : chip.endLine();
            int hitEnd = hit.endLine() == null ? hit.startLine() : hit.endLine();
            if (hit.startLine() <= chipEnd + NEARBY_LINES && chip.startLine() <= hitEnd + NEARBY_LINES) {
                return i;
            }
        }
        return -1;
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
