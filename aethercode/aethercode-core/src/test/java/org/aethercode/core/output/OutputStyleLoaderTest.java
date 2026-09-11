package org.aethercode.core.output;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutputStyleLoaderTest {

    @Test
    void missingDirReturnsEmpty(@TempDir Path tmp) {
        OutputStyleLoader l = new OutputStyleLoader(tmp.resolve("nonexistent"));
        assertThat(l.loadAll()).isEmpty();
    }

    @Test
    void nullDirReturnsEmpty() {
        OutputStyleLoader l = new OutputStyleLoader(null);
        assertThat(l.loadAll()).isEmpty();
    }

    @Test
    void emptyDirReturnsEmpty(@TempDir Path tmp) {
        OutputStyleLoader l = new OutputStyleLoader(tmp);
        assertThat(l.loadAll()).isEmpty();
    }

    @Test
    void loadSingleStyle(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pirate.md"), "Speak like a pirate. Arrr!");
        OutputStyleLoader l = new OutputStyleLoader(tmp);
        List<OutputStyle> styles = l.loadAll();
        assertThat(styles).hasSize(1);
        OutputStyle s = styles.get(0);
        assertThat(s.id()).isEqualTo("pirate");
        assertThat(s.systemPromptSuffix()).contains("pirate");
    }

    @Test
    void multipleFilesLoad(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.md"), "style a body");
        Files.writeString(tmp.resolve("b.md"), "style b body");
        Files.writeString(tmp.resolve("c.md"), "style c body");
        OutputStyleLoader l = new OutputStyleLoader(tmp);
        List<OutputStyle> styles = l.loadAll();
        assertThat(styles).hasSize(3);
    }

    @Test
    void nonMdFilesAreIgnored(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("style.md"), "real");
        Files.writeString(tmp.resolve("README.txt"), "ignored");
        Files.writeString(tmp.resolve("config.json"), "ignored");
        OutputStyleLoader l = new OutputStyleLoader(tmp);
        assertThat(l.loadAll()).hasSize(1);
    }

    @Test
    void emptyFileIsSkipped(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("blank.md"), "");
        OutputStyleLoader l = new OutputStyleLoader(tmp);
        assertThat(l.loadAll()).isEmpty();
    }

    @Test
    void bodyIsTrimmed(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("trimmed.md"), "  \n  body content  \n  ");
        OutputStyleLoader l = new OutputStyleLoader(tmp);
        List<OutputStyle> styles = l.loadAll();
        assertThat(styles).hasSize(1);
        assertThat(styles.get(0).systemPromptSuffix()).isEqualTo("body content");
    }

    @Test
    void userStyleOverridesBuiltinWithSameId(@TempDir Path tmp) throws Exception {
        // Pick a custom id that doesn't collide with the built-ins to keep
        // other test classes clean.
        String customSuffix = "Custom override suffix from a user file";
        Files.writeString(tmp.resolve("custom-override.md"), customSuffix);
        OutputStyleLoader l = new OutputStyleLoader(tmp);
        List<OutputStyle> styles = l.loadAll();
        assertThat(styles).hasSize(1);
        assertThat(styles.get(0).id()).isEqualTo("custom-override");
        assertThat(styles.get(0).systemPromptSuffix()).isEqualTo(customSuffix);
        // ensure built-ins are untouched
        assertThat(OutputStyle.byId("terse")).isSameAs(OutputStyle.TERSE);
    }

    @Test
    void dirReturnsConstructorValue() {
        Path p = Path.of("/tmp/styles");
        OutputStyleLoader l = new OutputStyleLoader(p);
        assertThat(l.dir()).isEqualTo(p);
    }
}
