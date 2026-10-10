package devPilot.backend.services.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class CodeChunkerTest {

    private final CodeChunker chunker = new CodeChunker(400, new CodeFileFilter());

    private static String javaClass() {
        StringBuilder src = new StringBuilder();
        src.append("package demo;\n\n");                              // 1-2
        src.append("import java.util.List;\n\n");                     // 3-4
        src.append("/** Limits calls. */\n");                         // 5
        src.append("public class RateLimiter {\n\n");                 // 6-7
        for (String name : List.of("acquire", "release", "reset")) {
            src.append("    /** ").append(name).append(" docs. */\n");
            src.append("    @Override\n");
            src.append("    public void ").append(name).append("() {\n");
            for (int i = 0; i < 6; i++) {
                src.append("        counter.").append(name).append("(").append(i).append(");\n");
            }
            src.append("    }\n\n");
        }
        src.append("}\n");
        return src.toString();
    }

    @Test
    void splitsAtMethodBoundariesAndKeepsDocsWithTheMethod() {
        List<Document> chunks = chunker.chunkFile("repo-1", "src/RateLimiter.java", javaClass());

        assertThat(chunks).hasSizeGreaterThan(1);
        for (Document chunk : chunks) {
            String text = chunk.getText();
            // No method is cut in half: every chunk that opens a method also closes it.
            long opens = text.lines().filter(l -> l.contains("public void")).count();
            long closes = text.lines().filter(l -> l.equals("    }")).count();
            assertThat(closes).isGreaterThanOrEqualTo(opens);
        }
        Document release = chunks.stream().filter(c -> c.getText().contains("public void release()")).findFirst().orElseThrow();
        assertThat(release.getText()).contains("/** release docs. */").contains("@Override");
    }

    @Test
    void recordsOneBasedLineRangesThatMatchTheSource() {
        String source = javaClass();
        String[] lines = source.split("\n", -1);
        List<Document> chunks = chunker.chunkFile("repo-1", "src/RateLimiter.java", source);

        int expectedStart = 1;
        for (Document chunk : chunks) {
            int start = (int) chunk.getMetadata().get("startLine");
            int end = (int) chunk.getMetadata().get("endLine");
            assertThat(start).isEqualTo(expectedStart);
            assertThat(end).isGreaterThanOrEqualTo(start);
            String firstCodeLine = chunk.getText().split("\n\n", 2)[1].split("\n", 2)[0];
            assertThat(firstCodeLine).isEqualTo(lines[start - 1]);
            expectedStart = end + 1;
        }
    }

    @Test
    void headerNamesFileLinesAndEnclosingType() {
        List<Document> chunks = chunker.chunkFile("repo-1", "src/RateLimiter.java", javaClass());
        Document last = chunks.get(chunks.size() - 1);

        assertThat(last.getText()).startsWith("File: src/RateLimiter.java (lines ");
        assertThat(last.getText()).contains("In: class RateLimiter");
        assertThat(last.getMetadata()).containsEntry("repoId", "repo-1")
                .containsEntry("filePath", "src/RateLimiter.java")
                .containsEntry("language", "java");
    }

    @Test
    void windowsAnOversizedUnitWithOverlap() {
        StringBuilder big = new StringBuilder("def huge():\n");
        for (int i = 0; i < 80; i++) {
            big.append("    value_").append(i).append(" = compute(").append(i).append(")\n");
        }
        List<Document> chunks = chunker.chunkFile("r", "big.py", big.toString());

        assertThat(chunks).hasSizeGreaterThan(3);
        int previousEnd = 0;
        for (Document chunk : chunks) {
            int start = (int) chunk.getMetadata().get("startLine");
            assertThat(start).isLessThanOrEqualTo(previousEnd + 1);
            previousEnd = (int) chunk.getMetadata().get("endLine");
            assertThat(chunk.getText().length()).isLessThan(400 + 120);
        }
    }

    @Test
    void splitsMarkdownByHeadings() {
        String md = "# Title\n\nintro\n\n## Install\n\n" + "step\n".repeat(60) + "\n## Usage\n\n" + "run it\n".repeat(40);
        List<Document> chunks = chunker.chunkFile("r", "README.md", md);

        assertThat(chunks.stream().anyMatch(c -> c.getText().contains("\n## Usage"))).isTrue();
        assertThat(chunks.stream().filter(c -> c.getText().contains("## Usage")).findFirst().orElseThrow().getText())
                .doesNotContain("## Install");
    }

    @Test
    void blankFilesProduceNothing() {
        assertThat(chunker.chunkFile("r", "a.java", "   \n\n")).isEmpty();
        assertThat(chunker.chunkFile("r", "a.java", null)).isEmpty();
    }

    @Test
    void declarationDetection() {
        assertThat(CodeChunker.isDeclaration("public class A {")).isTrue();
        assertThat(CodeChunker.isDeclaration("    private void run() {")).isTrue();
        assertThat(CodeChunker.isDeclaration("    List<String> names(int x) {")).isTrue();
        assertThat(CodeChunker.isDeclaration("export async function load() {")).isTrue();
        assertThat(CodeChunker.isDeclaration("def handler(event):")).isTrue();
        assertThat(CodeChunker.isDeclaration("func main() {")).isTrue();
        assertThat(CodeChunker.isDeclaration("const router = express();")).isTrue();

        assertThat(CodeChunker.isDeclaration("        return build(x);")).isFalse();
        assertThat(CodeChunker.isDeclaration("    private final int size;")).isFalse();
        assertThat(CodeChunker.isDeclaration("    return compute(a, b)")).isFalse();
        assertThat(CodeChunker.isDeclaration("    if (ready) {")).isFalse();
        assertThat(CodeChunker.isDeclaration("  const x = 1;")).isFalse();
    }
}
