package devPilot.backend.services.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.document.Document;

import devPilot.backend.dto.CitationDto;
import tools.jackson.databind.json.JsonMapper;

class CodeContextRetrieverTest {

    private final CodeContextRetriever retriever = new CodeContextRetriever(new CitationMapper(JsonMapper.builder().build()));

    private static Document chunk(String path, int start, int end) {
        return new Document("File: " + path + " (lines " + start + "-" + end + ")\n\ncode",
                Map.of("filePath", path, "startLine", start, "endLine", end, "language", "java"));
    }

    @Test
    void mergesHitsInTheSameFileIntoOneCitationKeepingRankOrder() {
        RetrievedContext context = retriever.build(List.of(
                chunk("src/B.java", 40, 60),
                chunk("src/A.java", 1, 20),
                chunk("src/B.java", 10, 25)));

        assertThat(context.citations()).containsExactly(
                new CitationDto("src/B.java", 10, 60, "java"),
                new CitationDto("src/A.java", 1, 20, "java"));
        assertThat(context.contextText()).contains("src/A.java (lines 1-20)").contains("---");
    }

    @Test
    void keepsHitsFarApartInOneFileAsSeparateCitations() {
        RetrievedContext context = retriever.build(List.of(
                chunk("src/JPF.java", 599, 643),
                chunk("src/A.java", 1, 20),
                chunk("src/JPF.java", 1, 39),
                chunk("src/JPF.java", 641, 661)));

        assertThat(context.citations()).containsExactly(
                new CitationDto("src/JPF.java", 599, 661, "java"),
                new CitationDto("src/A.java", 1, 20, "java"),
                new CitationDto("src/JPF.java", 1, 39, "java"));
    }

    @Test
    void saysSoWhenNothingMatched() {
        assertThat(retriever.build(List.of()).contextText()).isEqualTo("(no matching code chunks found)");
    }

    @Test
    void codeLinesCarryTheirLineNumbersSoCitationsCanBeExact() {
        String chunk = "File: src/A.java (lines 35-37)\nIn: class A\n\n\tint a;\n\tint b = 30;\n}\n";
        assertThat(CodeContextRetriever.numberLines(chunk))
                .isEqualTo("File: src/A.java (lines 35-37)\nIn: class A\n\n35| \tint a;\n36| \tint b = 30;\n37| }\n");
        assertThat(CodeContextRetriever.numberLines("plain text without a header")).isEqualTo("plain text without a header");
    }

    private static Document hit(String id) {
        return new Document(id, "File: src/" + id + ".java (lines 1-5)\n\ncode",
                Map.of("filePath", "src/" + id + ".java", "startLine", 1, "endLine", 5, "language", "java"));
    }

    private static List<String> ids(List<Document> docs) {
        return docs.stream().map(Document::getId).toList();
    }

    @Test
    void aFollowUpKeepsMostSlotsForItsOwnHitsAndAddsHitsFromTheEarlierQuestion() {
        List<Document> direct = List.of(hit("d1"), hit("d2"), hit("d3"), hit("d4"), hit("d5"), hit("d6"), hit("d7"), hit("d8"));
        List<Document> followUp = List.of(hit("d1"), hit("f1"), hit("f2"), hit("f3"));

        List<Document> merged = CodeContextRetriever.merge(direct, followUp);

        assertThat(ids(merged)).containsExactly("d1", "d2", "d3", "d4", "d5", "d6", "f1", "f2");
    }

    @Test
    void aFollowUpWithFewNewHitsFillsTheRestFromDirectHits() {
        List<Document> direct = List.of(hit("d1"), hit("d2"), hit("d3"), hit("d4"), hit("d5"), hit("d6"), hit("d7"), hit("d8"));

        List<Document> merged = CodeContextRetriever.merge(direct, List.of(hit("d2")));

        assertThat(ids(merged)).containsExactly("d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8");
    }

    @Test
    void aFollowUpAlsoSearchesWithTheEarlierQuestion() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit("d1")));

        retriever.retrieve(store, UUID.randomUUID(), "And where is it registered?", "What does PetValidator check?");

        ArgumentCaptor<SearchRequest> searches = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store, times(2)).similaritySearch(searches.capture());
        assertThat(searches.getAllValues().get(0).getQuery()).isEqualTo("And where is it registered?");
        assertThat(searches.getAllValues().get(1).getQuery())
                .contains("What does PetValidator check?").contains("And where is it registered?");
    }

    @Test
    void aFirstQuestionSearchesOnce() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit("d1")));

        retriever.retrieve(store, UUID.randomUUID(), "Where are owners saved?", null);

        verify(store, times(1)).similaritySearch(any(SearchRequest.class));
    }
}
