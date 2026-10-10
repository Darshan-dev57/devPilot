package devPilot.backend.services.indexing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which named type (class, interface, enum, record, struct, trait...) each line of
 * a source file sits in, so a chunk says "In: class ConfigListener" only while it really is
 * inside {@code ConfigListener}.
 *
 * <p>Brace languages are scanned character by character with a brace stack. Braces inside
 * string, text block, template and char literals and inside comments are ignored, so the
 * result does not depend on indentation: 2 spaces, 4 spaces and tabs all work. A brace only
 * names a type when the code just before it declares one; anonymous classes, lambdas,
 * methods and plain blocks count for depth but do not change the label.
 *
 * <p>Python and Ruby have no braces, so their scopes follow indentation instead.
 */
final class TypeScopes {

    private static final Set<String> INDENT_SCOPED = Set.of("py", "rb");

    private static final Set<String> SINGLE_QUOTE_STRINGS = Set.of(
            "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte", "php");

    private static final Set<String> NESTED_COMMENTS = Set.of("rs", "kt", "kts", "swift", "scala");

    private static final String MODIFIERS = "(?:(?:public|private|protected|internal|static|final|abstract|sealed"
            + "|non-sealed|open|data|inner|value|annotation|enum|case|export|default|declare|partial|readonly"
            + "|unsafe|strictfp|fileprivate|pub(?:\\([^)]*\\))?)\\s+)*";

    /** A header that declares a named type, once annotations and template prefixes are removed. */
    private static final Pattern TYPE_HEADER = Pattern.compile(
            "^" + MODIFIERS + "(class|interface|@interface|enum|record|object|struct|trait|impl|protocol|extension)"
                    + "\\b\\s*(?:<[^>]*>\\s*)?([A-Za-z_$][\\w$]*)");

    /** Go: {@code type Server struct}. */
    private static final Pattern GO_TYPE = Pattern.compile("^type\\s+([A-Za-z_]\\w*)\\s+(struct|interface)\\b");

    private static final Pattern LEADING_ANNOTATION = Pattern.compile(
            "^@(?!interface\\b)[\\w.$]+(?:\\s*\\((?:[^()]|\\([^()]*\\))*\\))?\\s*");

    private static final Pattern LEADING_ATTRIBUTE = Pattern.compile("^#\\[[^\\]]*\\]\\s*");

    private static final Pattern TEMPLATE_PREFIX = Pattern.compile("^template\\s*<[^>]*>\\s*");

    private static final Pattern PARENS = Pattern.compile("\\([^()]*\\)");

    private static final Pattern ANGLES = Pattern.compile("<[^<>]*>");

    private static final Pattern NOT_THE_FIRST_CODE = Pattern.compile(
            "^(?:(?:package|import|using|use|from|require|#include|#import|#pragma)\\b.*|[})\\];,\\s]*)$");

    private static final Pattern INDENT_TYPE = Pattern.compile("^(class|module)\\s+([A-Za-z_]\\w*(?:::\\w+)*)");

    /** Innermost named type open at the start of each line, or null. */
    private final String[] typeAtLine;

    /** Each line with comments removed and literals blanked, for checking what a line declares. */
    private final String[] codeLines;

    private TypeScopes(String[] typeAtLine, String[] codeLines) {
        this.typeAtLine = typeAtLine;
        this.codeLines = codeLines;
    }

    static TypeScopes of(String[] lines, String language) {
        String lang = language == null ? "" : language;
        return INDENT_SCOPED.contains(lang) ? indentScoped(lines) : braceScoped(lines, lang);
    }

    /** The named type whose body is open at the start of {@code line} (0-based), or null. */
    String enclosingTypeAt(int line) {
        if (line < 0 || line >= typeAtLine.length) {
            return null;
        }
        return typeAtLine[line];
    }

    /**
     * Label for a chunk covering lines [start, end): the type its first declaration belongs
     * to. A chunk that opens with a type declaration is labelled with that type, unless the
     * type opens and closes on that one line.
     */
    String labelFor(int start, int end) {
        int last = Math.min(end, codeLines.length);
        for (int i = Math.max(0, start); i < last; i++) {
            String code = stripPrefixes(codeLines[i]);
            if (NOT_THE_FIRST_CODE.matcher(code).matches()) {
                continue;
            }
            String declared = typeDeclaredBy(code);
            if (declared == null) {
                return enclosingTypeAt(i);
            }
            boolean closedOnThisLine = code.contains("{") && !declared.equals(enclosingTypeAt(i + 1));
            if (!closedOnThisLine) {
                return declared;
            }
            // A one-line type such as "static class Marker {}": label by what comes after it.
        }
        return enclosingTypeAt(start);
    }

    /** The type a declaration header names: "class Foo" for "public final class Foo extends Bar". */
    static String typeDeclaredBy(String header) {
        String code = stripPrefixes(header);
        String outer = code;
        for (String previous = null; !outer.equals(previous); ) {
            previous = outer;
            outer = ANGLES.matcher(PARENS.matcher(outer).replaceAll("")).replaceAll("");
        }
        if (outer.contains("=") && !outer.contains("=>")) {
            return null; // an initializer such as "struct Point p = {", not a declaration
        }
        Matcher go = GO_TYPE.matcher(code);
        if (go.find()) {
            return go.group(2) + " " + go.group(1);
        }
        Matcher type = TYPE_HEADER.matcher(code);
        if (!type.find()) {
            return null;
        }
        String keyword = type.group(1).equals("@interface") ? "interface" : type.group(1);
        return keyword + " " + type.group(2);
    }

    private static String stripPrefixes(String text) {
        String code = text.strip();
        for (String previous = null; !code.equals(previous); ) {
            previous = code;
            code = LEADING_ANNOTATION.matcher(code).replaceFirst("");
            code = LEADING_ATTRIBUTE.matcher(code).replaceFirst("");
            code = TEMPLATE_PREFIX.matcher(code).replaceFirst("");
        }
        return code;
    }

    // ------------------------------------------------------------------ brace languages

    private record Frame(String type, int savedParens, String savedHeader) {
    }

    private static TypeScopes braceScoped(String[] lines, String lang) {
        String text = String.join("\n", lines);
        String[] typeAtLine = new String[lines.length];
        StringBuilder[] code = new StringBuilder[lines.length];
        for (int i = 0; i < lines.length; i++) {
            code[i] = new StringBuilder();
        }

        boolean singleQuoteStrings = SINGLE_QUOTE_STRINGS.contains(lang);
        boolean nestedComments = NESTED_COMMENTS.contains(lang);
        boolean rawBackticks = "go".equals(lang);
        boolean hashComments = "php".equals(lang);

        Deque<Frame> stack = new ArrayDeque<>();
        StringBuilder header = new StringBuilder();
        int parens = 0;
        int line = 0;

        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';

            if (c == '\n') {
                line = newLine(typeAtLine, line, stack);
                header.append(' ');
                i++;
                continue;
            }

            int skipTo = -1;
            boolean comment = false;
            if (c == '/' && next == '/' || hashComments && c == '#' && next != '[') {
                int newline = text.indexOf('\n', i);
                skipTo = newline < 0 ? n : newline;
                comment = true;
            } else if (c == '/' && next == '*') {
                skipTo = endOfBlockComment(text, i, nestedComments);
                comment = true;
            } else if (c == '"' && text.startsWith("\"\"\"", i)) {
                int close = text.indexOf("\"\"\"", i + 3);
                skipTo = close < 0 ? n : close + 3;
            } else if (c == '"') {
                boolean verbatim = "cs".equals(lang) && i > 0 && text.charAt(i - 1) == '@';
                skipTo = verbatim ? endOfVerbatimString(text, i) : endOfQuoted(text, i, '"', true);
            } else if (c == '`') {
                skipTo = rawBackticks ? endOfRaw(text, i) : endOfTemplate(text, i);
            } else if (c == '\'') {
                skipTo = singleQuoteStrings
                        ? endOfQuoted(text, i, '\'', !"php".equals(lang))
                        : endOfCharLiteral(text, i); // -1 for a Rust lifetime such as 'a
            }
            if (skipTo > i) {
                // A literal or comment can span lines; keep the line numbering right.
                for (int k = i; k < skipTo; k++) {
                    if (text.charAt(k) == '\n') {
                        line = newLine(typeAtLine, line, stack);
                    }
                }
                if (comment) {
                    header.append(' ');
                } else {
                    header.append("\"\"");
                    code[Math.min(line, code.length - 1)].append("\"\"");
                }
                i = skipTo;
                continue;
            }

            code[Math.min(line, code.length - 1)].append(c);
            switch (c) {
                case '{' -> {
                    String type = parens == 0 ? typeDeclaredBy(header.toString()) : null;
                    stack.push(new Frame(type, parens, header.toString()));
                    parens = 0;
                    header.setLength(0);
                }
                case '}' -> {
                    header.setLength(0);
                    if (!stack.isEmpty()) {
                        Frame frame = stack.pop();
                        parens = frame.savedParens();
                        if (parens > 0) {
                            // A brace inside parentheses (a lambda argument, an annotation's
                            // array value): the surrounding header carries on after it.
                            header.append(frame.savedHeader()).append("{}");
                        }
                    }
                }
                case '(' -> {
                    parens++;
                    header.append(c);
                }
                case ')' -> {
                    parens = Math.max(0, parens - 1);
                    header.append(c);
                }
                case ';' -> {
                    if (parens == 0) {
                        header.setLength(0);
                    } else {
                        header.append(c);
                    }
                }
                default -> header.append(c);
            }
            i++;
        }

        String[] codeLines = new String[lines.length];
        for (int k = 0; k < lines.length; k++) {
            codeLines[k] = code[k].toString();
        }
        return new TypeScopes(typeAtLine, codeLines);
    }

    private static int newLine(String[] typeAtLine, int line, Deque<Frame> stack) {
        int next = line + 1;
        if (next < typeAtLine.length) {
            typeAtLine[next] = innermostType(stack);
        }
        return next;
    }

    private static String innermostType(Deque<Frame> stack) {
        for (Frame frame : stack) { // iterates from the top of the stack down
            if (frame.type() != null) {
                return frame.type();
            }
        }
        return null;
    }

    private static int endOfBlockComment(String text, int start, boolean nested) {
        int depth = 0;
        int i = start;
        while (i < text.length() - 1) {
            if (text.charAt(i) == '/' && text.charAt(i + 1) == '*' && (nested || depth == 0)) {
                depth++;
                i += 2;
            } else if (text.charAt(i) == '*' && text.charAt(i + 1) == '/') {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        return text.length();
    }

    /** End (exclusive) of a quoted string with backslash escapes. */
    private static int endOfQuoted(String text, int start, char quote, boolean stopAtNewline) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else if (c == '\n' && stopAtNewline) {
                return i; // unterminated: stop at the line end so one stray quote can't hide the file
            } else {
                i++;
            }
        }
        return text.length();
    }

    private static int endOfVerbatimString(String text, int start) {
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i) == '"') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return text.length();
    }

    private static int endOfRaw(String text, int start) {
        int close = text.indexOf('`', start + 1);
        return close < 0 ? text.length() : close + 1;
    }

    /** A JS template literal; its {@code ${...}} placeholders are skipped whole. */
    private static int endOfTemplate(String text, int start) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '`') {
                return i + 1;
            } else if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                int depth = 0;
                i++;
                while (i < text.length()) {
                    char d = text.charAt(i);
                    if (d == '{') {
                        depth++;
                    } else if (d == '}' && --depth == 0) {
                        break;
                    }
                    i++;
                }
                i++;
            } else {
                i++;
            }
        }
        return text.length();
    }

    /** A char literal such as 'x', '{', '\'' or '\u0041'; -1 when the quote is not one (a Rust lifetime). */
    private static int endOfCharLiteral(String text, int start) {
        int i = start + 1;
        if (i >= text.length()) {
            return -1;
        }
        if (text.charAt(i) == '\\') {
            int close = text.indexOf('\'', i + 2);
            int newline = text.indexOf('\n', i);
            if (close < 0 || newline >= 0 && newline < close || close - i > 12) {
                return -1;
            }
            return close + 1;
        }
        int cp = text.codePointAt(i);
        int after = i + Character.charCount(cp);
        if (cp != '\n' && after < text.length() && text.charAt(after) == '\'') {
            return after + 1;
        }
        return -1;
    }

    // ------------------------------------------------------------------ Python and Ruby

    private record Scope(int indent, String type) {
    }

    private static TypeScopes indentScoped(String[] lines) {
        String[] typeAtLine = new String[lines.length];
        String[] codeLines = new String[lines.length];
        Deque<Scope> stack = new ArrayDeque<>();
        String openTriple = null;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            boolean startsInString = openTriple != null;
            StringBuilder code = new StringBuilder();
            int k = 0;
            while (k < raw.length()) {
                if (openTriple != null) {
                    int close = raw.indexOf(openTriple, k);
                    if (close < 0) {
                        break;
                    }
                    k = close + 3;
                    openTriple = null;
                    code.append("\"\"");
                    continue;
                }
                char c = raw.charAt(k);
                if (c == '#') {
                    break;
                }
                if (raw.startsWith("\"\"\"", k) || raw.startsWith("'''", k)) {
                    openTriple = raw.substring(k, k + 3);
                    k += 3;
                    continue;
                }
                if (c == '"' || c == '\'') {
                    k = endOfQuoted(raw, k, c, true);
                    code.append("\"\"");
                    continue;
                }
                code.append(c);
                k++;
            }
            codeLines[i] = startsInString ? "" : code.toString();
            String stripped = codeLines[i].strip();
            if (!stripped.isEmpty()) {
                int indent = indent(raw);
                while (!stack.isEmpty() && indent <= stack.peek().indent()) {
                    stack.pop();
                }
            }
            typeAtLine[i] = stack.isEmpty() ? null : stack.peek().type();
            Matcher type = INDENT_TYPE.matcher(stripped);
            if (type.find()) {
                stack.push(new Scope(indent(raw), type.group(1) + " " + type.group(2)));
            }
        }
        return new TypeScopes(typeAtLine, codeLines);
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
}
