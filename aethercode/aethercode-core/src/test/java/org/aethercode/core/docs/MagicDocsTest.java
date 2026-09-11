package org.aethercode.core.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MagicDocsTest {

    private final MagicDocs m = new MagicDocs();

    @Test
    void emptySourceReturnsEmpty() {
        assertThat(m.extract(null)).isEmpty();
        assertThat(m.extract("")).isEmpty();
    }

    @Test
    void extractsClassName() {
        String src = "/**\n * A foo.\n */\npublic class Foo {\n}\n";
        List<MagicDocs.DocEntry> r = m.extract(src);
        assertThat(r).isNotEmpty();
        assertThat(r.get(0).name()).isEqualTo("Foo");
        assertThat(r.get(0).kind()).isEqualTo("class");
        assertThat(r.get(0).javadoc()).contains("A foo");
    }

    @Test
    void extractsInterface() {
        String src = "public interface Greeter {\n    void hello();\n}\n";
        List<MagicDocs.DocEntry> r = m.extract(src);
        assertThat(r).extracting(MagicDocs.DocEntry::name).contains("Greeter");
        assertThat(r).extracting(MagicDocs.DocEntry::kind).contains("interface");
    }

    @Test
    void extractsRecord() {
        String src = "public record Point(int x, int y) {}\n";
        List<MagicDocs.DocEntry> r = m.extract(src);
        assertThat(r).extracting(MagicDocs.DocEntry::name).contains("Point");
    }

    @Test
    void extractsPublicMethod() {
        String src = """
                public class C {
                    /** Says hi. */
                    public void hello() {}
                }
                """;
        List<MagicDocs.DocEntry> r = m.extract(src);
        assertThat(r).extracting(MagicDocs.DocEntry::name).contains("C", "hello");
        MagicDocs.DocEntry hello = r.stream().filter(e -> e.name().equals("hello")).findFirst().orElseThrow();
        assertThat(hello.kind()).isEqualTo("method");
        assertThat(hello.javadoc()).contains("Says hi");
    }

    @Test
    void renderProducesMarkdown() {
        String src = "/** doc */\npublic class C {}\n";
        List<MagicDocs.DocEntry> r = m.extract(src);
        String md = m.render("My API", r);
        assertThat(md).contains("# My API");
        assertThat(md).contains("## `C` (class)");
        assertThat(md).contains("```java");
        assertThat(md).contains("doc");
    }

    @Test
    void renderEmptyListStillProducesHeader() {
        String md = m.render("Empty", List.of());
        assertThat(md).contains("# Empty");
        assertThat(md).contains("No public declarations");
    }

    @Test
    void walkDirFindsAllClasses(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("A.java"), "/** a */\npublic class A {}\n");
        Files.writeString(tmp.resolve("B.java"), "public class B {}\n");
        Files.writeString(tmp.resolve("readme.txt"), "ignored");
        List<MagicDocs.DocEntry> r = m.walkDir(tmp);
        assertThat(r).extracting(MagicDocs.DocEntry::name).contains("A", "B");
    }

    @Test
    void walkDirMissingDirReturnsEmpty(@TempDir Path tmp) throws Exception {
        assertThat(m.walkDir(tmp.resolve("none"))).isEmpty();
    }

    @Test
    void javadocMultiline() {
        String src = """
                /**
                 * Line one
                 * Line two
                 * Line three
                 */
                public class Foo {}
                """;
        List<MagicDocs.DocEntry> r = m.extract(src);
        assertThat(r.get(0).javadoc()).contains("Line one").contains("Line three");
    }

    @Test
    void methodWithoutJavadocHasEmptyDoc() {
        String src = "public class C { public void noDoc() {} }\n";
        List<MagicDocs.DocEntry> r = m.extract(src);
        MagicDocs.DocEntry e = r.stream().filter(x -> x.name().equals("noDoc")).findFirst().orElseThrow();
        assertThat(e.javadoc()).isEmpty();
    }
}
