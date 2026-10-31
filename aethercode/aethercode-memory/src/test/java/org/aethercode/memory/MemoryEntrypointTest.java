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
