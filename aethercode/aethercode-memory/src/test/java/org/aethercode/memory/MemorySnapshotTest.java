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
