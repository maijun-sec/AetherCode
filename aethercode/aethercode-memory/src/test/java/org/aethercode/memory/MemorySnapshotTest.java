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

class MemorySnapshotTest {

    @Test
    void buildsAndRoundTrips(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("MEMORY.md"), "# index\n");
        Files.writeString(tmp.resolve("note-1.md"), "first note body");
        MemorySnapshot s = MemorySnapshot.build("test-agent", MemoryScope.USER, tmp);
        Path snap = tmp.resolve("snapshot.json");
        s.writeTo(snap);
        MemorySnapshot reloaded = MemorySnapshot.readFrom(snap);
        assertThat(reloaded.agentType).isEqualTo("test-agent");
        assertThat(reloaded.scope).isEqualTo(MemoryScope.USER);
        assertThat(reloaded.files).containsKeys("MEMORY.md", "note-1.md");
    }

    @Test
    void emptyDirProducesEmptySnapshot(@TempDir Path tmp) {
        MemorySnapshot s = MemorySnapshot.build("test-agent", MemoryScope.PROJECT, tmp);
        assertThat(s.files).isEmpty();
    }
}
