package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryDeduplicatorTest {

    @Test
    void similarTopicUpdatesExistingFile(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("java-streams.md"),
                "# Java Streams API\nCommon patterns for collection pipelines. Use stream on any Collection, then map/filter/reduce.\n");
        MemoryDeduplicator d = new MemoryDeduplicator(tmp);
        Path target = d.chooseTarget("java streams collection pipeline",
                "Use stream() on any Collection, then map/filter/reduce.");
        assertThat(target.getFileName().toString()).isEqualTo("java-streams.md");
    }

    @Test
    void unrelatedTopicCreatesNewFile(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("java-streams.md"),
                "# Java Streams API\nCommon patterns for collection pipelines.\n");
        MemoryDeduplicator d = new MemoryDeduplicator(tmp);
        Path target = d.chooseTarget("kotlin coroutines",
                "Cooperative concurrency with structured concurrency.");
        assertThat(target.getFileName().toString()).isNotEqualTo(Path.of("java-streams.md"));
        assertThat(target.getFileName().toString()).startsWith("kotlin-");
    }

    @Test
    void slugifyNormalisesTitle() {
        assertThat(MemoryDeduplicator.slugify("Foo Bar Baz")).isEqualTo("foo-bar-baz");
        assertThat(MemoryDeduplicator.slugify("C++ Templates!")).isEqualTo("c-templates");
    }
}
