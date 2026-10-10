package devPilot.backend.services.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
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
}
