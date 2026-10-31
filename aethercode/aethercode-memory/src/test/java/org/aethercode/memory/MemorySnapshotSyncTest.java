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

class MemorySnapshotSyncTest {

    @Test
    void diffReportsAddsAndModifies(@TempDir Path tmp) throws Exception {
        Path memoryDir = tmp.resolve("mem");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("MEMORY.md"), "# index\n");
        Files.writeString(memoryDir.resolve("a.md"), "version 1");
        MemorySnapshot local = MemorySnapshot.build("agent", MemoryScope.USER, memoryDir);
        // Simulate a remote snapshot that's newer and has more files.
        Files.writeString(memoryDir.resolve("b.md"), "new file");
        Files.writeString(memoryDir.resolve("a.md"), "version 2");
        MemorySnapshot remote = MemorySnapshot.build("agent", MemoryScope.USER, memoryDir);
        MemorySnapshotSync sync = new MemorySnapshotSync(tmp, memoryDir);
        String diff = sync.diff(local, remote);
        assertThat(diff).contains("b.md").contains("a.md");
    }

    @Test
    void diffReportsMatchesWhenEqual(@TempDir Path tmp) throws Exception {
        Path memoryDir = tmp.resolve("mem");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("MEMORY.md"), "# index\n");
        Files.writeString(memoryDir.resolve("a.md"), "same");
        MemorySnapshot s1 = MemorySnapshot.build("agent", MemoryScope.USER, memoryDir);
        MemorySnapshot s2 = MemorySnapshot.build("agent", MemoryScope.USER, memoryDir);
        MemorySnapshotSync sync = new MemorySnapshotSync(tmp, memoryDir);
        assertThat(sync.diff(s1, s2)).isEqualTo("snapshots match");
    }
}
