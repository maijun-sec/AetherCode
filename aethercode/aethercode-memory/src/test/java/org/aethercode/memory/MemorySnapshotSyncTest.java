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
