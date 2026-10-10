package devPilot.backend.services.indexing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import devPilot.backend.services.ai.RagSettings;

/**
 * Splits a source file into chunks that follow the code's own structure.
 *
 * <p>Instead of cutting every N tokens (which slices methods in half), it finds the lines
 * where a class, method or function starts, treats the code between two such lines as one
 * unit, and packs whole units into chunks of up to {@code chunk-size} characters. A unit
 * that is too big on its own is split by lines with a small overlap. Comments and
 * annotations directly above a declaration stay with it.
 *
 * <p>Every chunk records its line range, so answers can link to the exact lines on GitHub,
 * and a short header (file path, enclosing type, first declaration) is embedded with the
 * code, which helps retrieval match questions like "where is the rate limiter". The enclosing
 * type comes from {@link TypeScopes}, which tracks brace depth, so it stays right after an
 * inner class closes and for any indentation.
 *
 * <p>It is a line-based heuristic, not a parser: it works the same way for Java, Kotlin,
 * TypeScript, Python, Go, Rust and similar languages, and degrades to plain line windows
 * for everything else.
 */
@Component
public class CodeChunker {

    private static final Set<String> STRUCTURED = Set.of(
            "java", "kt", "kts", "scala", "ts", "tsx", "js", "jsx", "mjs", "cjs",
            "py", "go", "rs", "rb", "php", "c", "h", "cpp", "hpp", "cs", "swift",
            "m", "mm", "vue", "svelte");

    private static final Pattern DECLARATION = Pattern.compile(
            "^(export\\s+)?(default\\s+)?"
                    + "(public|private|protected|internal|static|final|abstract|async|override|open|sealed"
                    + "|data|suspend|inline|pub(\\([a-z]+\\))?|fn|fun|func|def|class|interface|enum|record|struct"
                    + "|trait|impl|object|type|function|module|namespace|@interface)\\b.*");

    /** Typed method signature without modifiers, e.g. {@code void run() {} or {@code List<X> find(}. */
    private static final Pattern TYPED_SIGNATURE = Pattern.compile(
            "^[A-Za-z_][\\w<>\\[\\],.? ]*\\s+[A-Za-z_]\\w*\\s*\\([^;]*$");

    private static final Pattern TOP_LEVEL_BINDING = Pattern.compile("^(export\\s+)?(const|let|var)\\s+\\w+.*");

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,3}\\s+\\S.*");

    private static final Set<String> NOT_A_SIGNATURE = Set.of(
            "return", "new", "throw", "else", "if", "for", "while", "switch", "case", "catch",
            "await", "yield", "typeof", "delete", "do", "try", "assert", "import", "package", "using");

    /** Declarations deeper than this are inside a method body and are not split points. */
    private static final int MAX_DECLARATION_INDENT = 4;

    private static final int OVERLAP_LINES = 3;

    private final int maxChars;
    private final CodeFileFilter fileFilter;

    public CodeChunker(
            @Value("${app.indexing.chunk-size:1500}") int maxChars,
            CodeFileFilter fileFilter) {
        this.maxChars = Math.max(300, maxChars);
        this.fileFilter = fileFilter;
    }

    public List<Document> chunkFile(String repoId, String filePath, String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String language = fileFilter.detectLanguage(filePath);
        String[] lines = content.replace("\r\n", "\n").split("\n", -1);

        TypeScopes scopes = STRUCTURED.contains(language) ? TypeScopes.of(lines, language) : null;
        List<Document> documents = new ArrayList<>();
        int index = 0;
        for (Span span : split(lines, language)) {
            String body = join(lines, span.start, span.end);
            if (body.isBlank()) {
                continue;
            }
            String header = header(filePath, scopes, span);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(RagSettings.METADATA_REPO_ID, repoId);
            metadata.put(RagSettings.METADATA_FILE_PATH, filePath);
            metadata.put("language", language);
            metadata.put("chunkIndex", index++);
            metadata.put("startLine", span.start + 1);
            metadata.put("endLine", span.end);
            documents.add(new Document(header + body, metadata));
        }
        return documents;
    }

    /** Chunk boundaries as half-open line ranges [start, end). Visible for tests. */
    List<Span> split(String[] lines, String language) {
        List<Span> units = units(lines, language);
        List<Span> chunks = new ArrayList<>();
        int start = -1;
        int end = -1;
        int size = 0;
        for (Span unit : units) {
            int unitSize = chars(lines, unit.start, unit.end);
            if (unitSize > maxChars) {
                if (start >= 0) {
                    chunks.add(new Span(start, end));
                    start = -1;
                    size = 0;
                }
                chunks.addAll(window(lines, unit));
                continue;
            }
            if (start >= 0 && size + unitSize > maxChars) {
                chunks.add(new Span(start, end));
                start = -1;
                size = 0;
            }
            if (start < 0) {
                start = unit.start;
            }
            end = unit.end;
            size += unitSize;
        }
        if (start >= 0) {
            chunks.add(new Span(start, end));
        }
        return mergeTinyTail(lines, chunks);
    }

    private List<Span> units(String[] lines, String language) {
        TreeSet<Integer> boundaries = new TreeSet<>();
        boundaries.add(0);
        boolean markdown = "md".equals(language) || "mdx".equals(language);
        boolean structured = STRUCTURED.contains(language);
        if (markdown || structured) {
            for (int i = 1; i < lines.length; i++) {
                if (markdown ? MARKDOWN_HEADING.matcher(lines[i]).matches() : isDeclaration(lines[i])) {
                    boundaries.add(attachLeadingComments(lines, i));
                }
            }
        }
        List<Span> units = new ArrayList<>();
        Integer previous = null;
        for (int boundary : boundaries) {
            if (previous != null && boundary > previous) {
                units.add(new Span(previous, boundary));
            }
            previous = boundary;
        }
        units.add(new Span(previous, lines.length));
        return units;
    }

    static boolean isDeclaration(String line) {
        if (line.isBlank()) {
            return false;
        }
        int indent = indent(line);
        if (indent > MAX_DECLARATION_INDENT) {
            return false;
        }
        String code = line.strip();
        if (indent == 0 && TOP_LEVEL_BINDING.matcher(code).matches()) {
            return true;
        }
        if (DECLARATION.matcher(code).matches()) {
            return !code.endsWith(";") || code.contains("(") && code.contains("{");
        }
        if (TYPED_SIGNATURE.matcher(code).matches()) {
            String first = code.split("[\\s(<]", 2)[0];
            return !NOT_A_SIGNATURE.contains(first) && !code.contains("=");
        }
        return false;
    }

    /** Moves a boundary up over the comments and annotations that belong to the declaration. */
    private static int attachLeadingComments(String[] lines, int declaration) {
        int start = declaration;
        while (start > 0) {
            String above = lines[start - 1].strip();
            boolean attached = above.startsWith("@") || above.startsWith("//") || above.startsWith("/*")
                    || above.startsWith("*") || above.startsWith("#[") || above.startsWith("///")
                    || (above.startsWith("#") && !above.startsWith("#include") && !above.startsWith("#!"));
            if (!attached) {
                break;
            }
            start--;
        }
        return start;
    }

    private List<Span> window(String[] lines, Span unit) {
        List<Span> windows = new ArrayList<>();
        int start = unit.start;
        while (start < unit.end) {
            int end = start;
            int size = 0;
            while (end < unit.end && (end == start || size + lines[end].length() + 1 <= maxChars)) {
                size += lines[end].length() + 1;
                end++;
            }
            windows.add(new Span(start, end));
            if (end >= unit.end) {
                break;
            }
            start = Math.max(start + 1, end - OVERLAP_LINES);
        }
        return windows;
    }

    private List<Span> mergeTinyTail(String[] lines, List<Span> chunks) {
        if (chunks.size() < 2) {
            return chunks;
        }
        Span last = chunks.get(chunks.size() - 1);
        Span beforeLast = chunks.get(chunks.size() - 2);
        if (last.start >= beforeLast.end
                && chars(lines, last.start, last.end) < maxChars / 6
                && chars(lines, beforeLast.start, last.end) <= maxChars * 5 / 4) {
            List<Span> merged = new ArrayList<>(chunks.subList(0, chunks.size() - 2));
            merged.add(new Span(beforeLast.start, last.end));
            return merged;
        }
        return chunks;
    }

    private static String header(String filePath, TypeScopes scopes, Span span) {
        StringBuilder header = new StringBuilder("File: ").append(filePath)
                .append(" (lines ").append(span.start + 1).append('-').append(span.end).append(")\n");
        String type = scopes == null ? null : scopes.labelFor(span.start, span.end);
        if (type != null) {
            header.append("In: ").append(type).append('\n');
        }
        return header.append('\n').toString();
    }

    private static int indent(String line) {
        int width = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                width++;
            } else if (c == '\t') {
                width += 4;
            } else {
                break;
            }
        }
        return width;
    }

    private static int chars(String[] lines, int start, int end) {
        int total = 0;
        for (int i = start; i < end; i++) {
            total += lines[i].length() + 1;
        }
        return total;
    }

    private static String join(String[] lines, int start, int end) {
        return String.join("\n", java.util.Arrays.copyOfRange(lines, start, end));
    }

    record Span(int start, int end) {
    }
}
