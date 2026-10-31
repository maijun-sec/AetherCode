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

package org.aethercode.tasks.engine.checkpoint;

import org.aethercode.tasks.engine.checkpoint.CompressionUtil.CompressionAlgo;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-1-10: 4 tests for {@link CompressionUtil}.
 */
class CompressionUtilTest {

    @Test
    void smallPayloadIsReturnedUntouched() {
        // Below the 256 KB threshold: tagged NONE, no body shrinkage.
        byte[] raw = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] out = CompressionUtil.compress(raw);
        assertThat(out).hasSize(raw.length + 1);
        assertThat(out[0]).isEqualTo(CompressionAlgo.NONE.tag());
        assertThat(CompressionUtil.decompress(out)).isEqualTo(raw);
    }

    @Test
    void largePayloadIsCompressedAndRoundTrips() {
        // 1 MB of repetitive data — the easy case for both Deflate and zstd.
        byte[] raw = new byte[1024 * 1024];
        byte[] pattern = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < raw.length; i += pattern.length) {
            System.arraycopy(pattern, 0, raw, i, Math.min(pattern.length, raw.length - i));
        }
        byte[] out = CompressionUtil.compress(raw);
        // Whether zstd or deflate, the output should be much smaller.
        assertThat(out.length).isLessThan(raw.length / 5);
        assertThat(out[0]).isNotEqualTo(CompressionAlgo.NONE.tag());
        // round-trip must be lossless
        byte[] back = CompressionUtil.decompress(out);
        assertThat(back).isEqualTo(raw);
    }

    @Test
    void largeIncompressiblePayloadStillRoundTrips() {
        // A payload above the threshold but incompressible (random bytes).
        // Compression ratio is ~1:1 but the round-trip must still hold.
        byte[] raw = new byte[512 * 1024];
        new Random(42L).nextBytes(raw);
        byte[] out = CompressionUtil.compress(raw);
        assertThat(out).isNotEqualTo(raw);
        assertThat(CompressionUtil.decompress(out)).isEqualTo(raw);
    }

    @Test
    void forceCompressWithDeflateExplicitlyWorks() {
        // forceCompress ignores the threshold; we test the explicit
        // DEFLATE path even if zstd is on the classpath.
        byte[] raw = "repeat ".repeat(1000).getBytes(StandardCharsets.UTF_8);
        byte[] out = CompressionUtil.forceCompress(raw, CompressionAlgo.DEFLATE);
        assertThat(out[0]).isEqualTo(CompressionAlgo.DEFLATE.tag());
        assertThat(CompressionUtil.decompress(out)).isEqualTo(raw);
    }

    @Test
    void emptyInputRejected() {
        assertThatThrownBy(() -> CompressionUtil.compress(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompressionUtil.decompress(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
