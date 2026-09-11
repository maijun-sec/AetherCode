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
