/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
