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

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRecallTest {

    @Test
    void picksTopNByLexicalMatch(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("java-streams.md"),
                "# Java Streams API\n" +
                "Common patterns for collection pipelines.\n" +
                "Use stream() on any Collection, then map/filter/reduce.\n");
        Files.writeString(tmp.resolve("kotlin-coroutines.md"),
                "# Kotlin coroutines\n" +
                "Cooperative concurrency with structured concurrency.\n" +
                "Use launch / async / runBlocking.\n");
        Files.writeString(tmp.resolve("maven-build.md"),
                "# Maven build profile\n" +
                "Activate via -Pprofile-name or via settings.xml.\n");
        Files.writeString(MemoryPaths.entrypoint(tmp),
                "- [java-streams](java-streams.md) — collection pipelines\n" +
                "- [kotlin-coroutines](kotlin-coroutines.md) — async coroutines\n" +
                "- [maven-build](maven-build.md) — build profiles\n");

        MemoryRecall recaller = new MemoryRecall();
        var hits = recaller.recall(tmp, "how do I use java streams", List.of(), List.of());
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).name).isEqualTo("java-streams.md");
    }

    @Test
    void emptyDirReturnsEmpty(@TempDir Path tmp) {
        MemoryRecall recaller = new MemoryRecall();
        var hits = recaller.recall(tmp, "anything", List.of(), List.of());
        assertThat(hits).isEmpty();
    }

    @Test
    void skipsAlreadySurfaced(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.md"), "A note about foo.\n");
        Files.writeString(tmp.resolve("b.md"), "B note about foo.\n");
        MemoryRecall recaller = new MemoryRecall();
        var hits = recaller.recall(tmp, "foo",
                List.of(), List.of(tmp.resolve("a.md").toAbsolutePath()));
        assertThat(hits).extracting(h -> h.name).doesNotContain("a.md");
    }

    @Test
    void sideQueryLimitsResultsWhenCandidatesExceedThreshold(@TempDir Path tmp) throws Exception {
        for (int i = 0; i < 20; i++) {
            Files.writeString(tmp.resolve("topic-" + i + ".md"),
                    "# topic " + i + "\nnotes about random " + i + ".\n");
        }
        ChatClient sideClient = new ChatClient() {
            public String modelId() { return "mock"; }
            public Stream<StreamEvent> stream(java.util.List<Message> messages, String systemPrompt,
                                              java.util.List<org.aethercode.core.tool.Tool> tools) {
                // Return first 3 names
                return Stream.of(
                        new StreamEvent.TextDelta("{\"files\":[\"topic-0.md\",\"topic-1.md\",\"topic-2.md\"]}"),
                        new StreamEvent.RunEnd("end_turn", List.of())
                );
            }
        };
        MemoryRecall recaller = new MemoryRecall(sideClient);
        var hits = recaller.recall(tmp, "random", List.of(), List.of());
        assertThat(hits).isNotEmpty();
        assertThat(hits.size()).isLessThanOrEqualTo(MemoryRecall.MAX_RECALL);
    }
}
