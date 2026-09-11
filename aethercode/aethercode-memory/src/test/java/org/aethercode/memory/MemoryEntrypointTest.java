package org.aethercode.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryEntrypointTest {

    @Test
    void truncatesBeyondLineCap() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) big.append("line-").append(i).append('\n');
        var t = MemoryEntrypoint.truncate(big.toString());
        assertThat(t.wasLineTruncated()).isTrue();
        assertThat(t.lineCount()).isEqualTo(MemoryPaths.MAX_ENTRYPOINT_LINES);
    }

    @Test
    void truncatesBeyondByteCap() {
        StringBuilder big = new StringBuilder();
        // one very long line
        big.append("x".repeat(MemoryPaths.MAX_ENTRYPOINT_BYTES + 1000));
        var t = MemoryEntrypoint.truncate(big.toString());
        assertThat(t.wasByteTruncated()).isTrue();
        assertThat(t.byteCount()).isLessThanOrEqualTo(MemoryPaths.MAX_ENTRYPOINT_BYTES);
    }

    @Test
    void shortContentNotTruncated() {
        var t = MemoryEntrypoint.truncate("hello\nworld\n");
        assertThat(t.wasLineTruncated()).isFalse();
        assertThat(t.wasByteTruncated()).isFalse();
        assertThat(t.content()).isEqualTo("hello\nworld\n");
    }

    @Test
    void memoryPathsSanitisesAgentType() {
        assertThat(MemoryPaths.sanitize("plugin:my-agent")).isEqualTo("plugin-my-agent");
    }
}
