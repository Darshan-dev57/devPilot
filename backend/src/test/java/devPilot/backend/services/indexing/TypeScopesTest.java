package devPilot.backend.services.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;

class TypeScopesTest {

    /** Shaped like jpf-core's JPF.java: 2-space indent, an inner class, then more methods. */
    private static final String JPF = """
            package gov.nasa.jpf;

            import java.util.List;

            /** The main class. */
            public class JPF implements Runnable {
              static class ConfigListener implements ConfigChangeListener {
                @Override
                public void propertyChanged(Config conf, String key) {
                  if (key.equals("target")) {
                    conf.reset();
                  }
                }
              }

              public JPF(Config config) {
                this.config = config;
              }

              public void run() {
                search.start();
              }

              public static void main(String[] args) {
                new JPF(null).run();
              }
            }
            """;

    private static String[] lines(String source) {
        return source.split("\n", -1);
    }

    private static int lineOf(String[] lines, String needle) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("no line contains " + needle);
    }

    /** Re-indents a 2-space source with the given unit (2 spaces, 4 spaces or a tab). */
    private static String reindent(String source, String unit) {
        StringBuilder out = new StringBuilder();
        for (String line : lines(source)) {
            int spaces = 0;
            while (spaces < line.length() && line.charAt(spaces) == ' ') {
                spaces++;
            }
            out.append(unit.repeat(spaces / 2)).append(line.substring(spaces)).append('\n');
        }
        return out.toString();
    }

    private static String typeAt(String source, String language, String needle) {
        String[] lines = lines(source);
        return TypeScopes.of(lines, language).enclosingTypeAt(lineOf(lines, needle));
    }

    @ParameterizedTest
    @ValueSource(strings = { "  ", "    ", "\t" })
    void methodsAfterAnInnerClassBelongToTheOuterClassForAnyIndentation(String unit) {
        String source = reindent(JPF, unit);

        assertThat(typeAt(source, "java", "public void propertyChanged")).isEqualTo("class ConfigListener");
        assertThat(typeAt(source, "java", "conf.reset()")).isEqualTo("class ConfigListener");
        assertThat(typeAt(source, "java", "public JPF(Config config)")).isEqualTo("class JPF");
        assertThat(typeAt(source, "java", "public void run()")).isEqualTo("class JPF");
        assertThat(typeAt(source, "java", "public static void main")).isEqualTo("class JPF");
        assertThat(typeAt(source, "java", "package gov.nasa.jpf")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = { "  ", "    ", "\t" })
    void aChunkThatOpensWithATypeIsLabelledWithThatType(String unit) {
        String[] lines = lines(reindent(JPF, unit));
        TypeScopes scopes = TypeScopes.of(lines, "java");

        assertThat(scopes.labelFor(0, lines.length)).isEqualTo("class JPF");
        assertThat(scopes.labelFor(lineOf(lines, "static class ConfigListener"), lines.length))
                .isEqualTo("class ConfigListener");
        assertThat(scopes.labelFor(lineOf(lines, "@Override"), lines.length)).isEqualTo("class ConfigListener");
        // Starting on the inner class's closing brace still labels the next method by its own class.
        assertThat(scopes.labelFor(lineOf(lines, "public JPF(Config config)") - 2, lines.length))
                .isEqualTo("class JPF");
    }

    @Test
    void bracesInStringsCharsTextBlocksAndCommentsAreIgnored() {
        String source = """
                public class Tricky {
                  private static final String OPEN = "{ class Fake {";
                  private static final char BRACE = '}';
                  private static final char QUOTE = '\\'';
                  private static final char SLASH = '\\\\';
                  private static final String ESCAPED = "\\"}";
                  // } a closing brace in a comment
                  /* class Ghost { */
                  /**
                   * } and { in Javadoc
                   */
                  private static final String BLOCK = \"""
                      }
                      class InTextBlock {
                      \""";
                  class Inner {
                    void a() {}
                  }
                  void after() {}
                }
                """;

        assertThat(typeAt(source, "java", "void a()")).isEqualTo("class Inner");
        assertThat(typeAt(source, "java", "void after()")).isEqualTo("class Tricky");
        assertThat(typeAt(source, "java", "class Inner {")).isEqualTo("class Tricky");
        assertThat(typeAt(source, "java", "private static final String BLOCK")).isEqualTo("class Tricky");
    }

    @Test
    void anonymousClassesAndLambdasDoNotChangeTheLabel() {
        String source = """
                public class Server {
                  private final Runnable task = new Runnable() {
                    @Override
                    public void run() {
                      work();
                    }
                  };

                  void start() {
                    executor.submit(new Callable<String>() {
                      public String call() { return "ok"; }
                    });
                    list.forEach(x -> {
                      process(x);
                    });
                  }

                  void stop() {
                  }
                }
                """;

        assertThat(typeAt(source, "java", "public void run()")).isEqualTo("class Server");
        assertThat(typeAt(source, "java", "public String call()")).isEqualTo("class Server");
        assertThat(typeAt(source, "java", "process(x)")).isEqualTo("class Server");
        assertThat(typeAt(source, "java", "void stop()")).isEqualTo("class Server");
    }

    @Test
    void enumsWithConstantBodiesAndNestedEnums() {
        String source = """
                public enum Planet {
                  MERCURY(3.303e+23) {
                    @Override
                    double gravity() { return 1; }
                  },
                  EARTH(5.976e+24);

                  private final double mass;

                  Planet(double mass) { this.mass = mass; }

                  enum Unit { KG, LB }

                  double inKg() { return mass; }
                }

                class Other {
                  void x() {}
                }
                """;
        String[] lines = lines(source);
        TypeScopes scopes = TypeScopes.of(lines, "java");

        assertThat(typeAt(source, "java", "double gravity()")).isEqualTo("enum Planet");
        assertThat(typeAt(source, "java", "EARTH(")).isEqualTo("enum Planet");
        assertThat(scopes.labelFor(lineOf(lines, "enum Unit"), lines.length)).isEqualTo("enum Unit");
        assertThat(typeAt(source, "java", "double inKg()")).isEqualTo("enum Planet");
        assertThat(typeAt(source, "java", "void x()")).isEqualTo("class Other");
    }

    @Test
    void recordsInterfacesAndAnnotationArrays() {
        String source = """
                @SuppressWarnings({"unchecked", "rawtypes"})
                public class Config {
                  public record Entry(String key, String value) {
                    public Entry {
                      Objects.requireNonNull(key);
                    }
                  }

                  public interface Listener {
                    void changed(Entry e);
                  }

                  public @interface Marker {
                  }

                  public Entry get(String key) { return null; }
                }
                """;

        assertThat(typeAt(source, "java", "requireNonNull")).isEqualTo("record Entry");
        assertThat(typeAt(source, "java", "void changed")).isEqualTo("interface Listener");
        assertThat(typeAt(source, "java", "public Entry get")).isEqualTo("class Config");
        assertThat(TypeScopes.typeDeclaredBy("public @interface Marker")).isEqualTo("interface Marker");
    }

    @Test
    void typeScriptTemplatesAndRustLifetimes() {
        String ts = """
                export class Api {
                  url = `${base}/repos/{id}`;
                  get() { return `}`; }
                }
                export function after() {}
                """;
        assertThat(typeAt(ts, "ts", "get()")).isEqualTo("class Api");
        assertThat(typeAt(ts, "ts", "export function after")).isNull();

        String rust = """
                impl<'a> Parser<'a> {
                    fn next(&mut self) -> Option<&'a str> {
                        let c = '{';
                        None
                    }
                }
                fn free() {}
                """;
        assertThat(typeAt(rust, "rs", "fn next")).isEqualTo("impl Parser");
        assertThat(typeAt(rust, "rs", "fn free")).isNull();
    }

    @Test
    void pythonScopesFollowIndentation() {
        String source = """
                class Outer:
                    \"""Docs.

                class NotAClass:
                    \"""
                    class Inner:
                        def a(self):
                            return "}"

                    def b(self):
                        pass

                def free():
                    pass
                """;

        assertThat(typeAt(source, "py", "def a(self)")).isEqualTo("class Inner");
        assertThat(typeAt(source, "py", "def b(self)")).isEqualTo("class Outer");
        assertThat(typeAt(source, "py", "def free()")).isNull();
    }

    @Test
    void notADeclaration() {
        assertThat(TypeScopes.typeDeclaredBy("if (object instanceof Foo) ")).isNull();
        assertThat(TypeScopes.typeDeclaredBy("Runnable r = new Runnable() ")).isNull();
        assertThat(TypeScopes.typeDeclaredBy("struct Point p = ")).isNull();
        assertThat(TypeScopes.typeDeclaredBy("String name = Foo.class.getName() + ")).isNull();
        assertThat(TypeScopes.typeDeclaredBy("type Server struct ")).isEqualTo("struct Server");
        assertThat(TypeScopes.typeDeclaredBy("export default class Foo<T = string> extends Bar "))
                .isEqualTo("class Foo");
    }

    @Test
    void chunkHeadersOfATwoSpaceFileNameTheRightClass() {
        StringBuilder source = new StringBuilder("package demo;\n\npublic class JPF {\n");
        source.append("  static class ConfigListener {\n");
        source.append("    public void propertyChanged(String key) {\n");
        for (int i = 0; i < 8; i++) {
            source.append("      log.info(\"changed {}\", key").append(i).append(");\n");
        }
        source.append("    }\n  }\n\n");
        for (String name : List.of("run", "addListener", "getSearch", "printBanner", "exit")) {
            source.append("  public void ").append(name).append("() {\n");
            for (int i = 0; i < 8; i++) {
                source.append("    state.").append(name).append("(").append(i).append(");\n");
            }
            source.append("  }\n\n");
        }
        source.append("}\n");

        List<Document> chunks = new CodeChunker(300, new CodeFileFilter())
                .chunkFile("repo-1", "src/main/gov/nasa/jpf/JPF.java", source.toString());

        assertThat(chunks).hasSizeGreaterThan(3);
        for (Document chunk : chunks) {
            String text = chunk.getText();
            if (text.contains("public void propertyChanged")) {
                assertThat(text).contains("In: class ConfigListener");
            } else if (text.contains("public void exit()") || text.contains("public void getSearch()")) {
                assertThat(text).contains("In: class JPF").doesNotContain("ConfigListener");
            }
        }
    }

    @Test
    void markdownChunksGetNoTypeLabel() {
        List<Document> chunks = new CodeChunker(300, new CodeFileFilter())
                .chunkFile("repo-1", "README.md", "# Setup\n\nThe main class JPF starts the search.\n");

        assertThat(chunks).singleElement().satisfies(c -> assertThat(c.getText()).doesNotContain("In: "));
    }
}
