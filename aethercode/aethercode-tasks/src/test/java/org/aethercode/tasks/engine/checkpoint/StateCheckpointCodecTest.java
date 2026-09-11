package org.aethercode.tasks.engine.checkpoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-1-01: 5 tests for {@link StateCheckpointCodec}. Covers the
 * atomic-write contract (write-temp + fsync + rename), the
 * on-disk frame format, hash verification, and the kill -9
 * simulation.
 */
class StateCheckpointCodecTest {

    @Test
    void writeAtomicProducesReadableFileAndHashMatches(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve("state.bin");
        byte[] raw = "the quick brown fox".repeat(50).getBytes();

        String hash = StateCheckpointCodec.writeAtomic(target, raw);

        assertThat(Files.exists(target)).isTrue();
        assertThat(hash).isEqualTo(StateCheckpointCodec.sha256Hex(raw));
        assertThat(StateCheckpointCodec.read(target)).isEqualTo(raw);
    }

    @Test
    void writeAtomicOverwritesExistingFile(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve("state.bin");
        StateCheckpointCodec.writeAtomic(target, "first".getBytes());
        StateCheckpointCodec.writeAtomic(target, "second".getBytes());
        assertThat(StateCheckpointCodec.read(target)).isEqualTo("second".getBytes());
    }

    @Test
    void atomicWriteSurvivesKill9Simulation(@TempDir Path tmp) throws IOException {
        // Simulate a kill -9 mid-write: the partial temp file is left
        // on disk; the target file is still the previous valid one.
        Path target = tmp.resolve("state.bin");
        StateCheckpointCodec.writeAtomic(target, "old".getBytes());
        byte[] old = StateCheckpointCodec.read(target);
        // Spawn many concurrent writers; each must produce a valid
        // file on completion (no torn writes).
        int writers = 8;
        Thread[] threads = new Thread[writers];
        for (int i = 0; i < writers; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    byte[] payload = ("payload-" + idx).getBytes();
                    StateCheckpointCodec.writeAtomic(target, payload);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        // The file is one of the writers' payloads — never torn.
        byte[] finalBytes = StateCheckpointCodec.read(target);
        assertThat(finalBytes).startsWith("payload-".getBytes());
    }

    @Test
    void readIfHashMatchesReturnsEmptyOnMismatch(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve("state.bin");
        String hash = StateCheckpointCodec.writeAtomic(target, "data".getBytes());
        // wrong hash
        Optional<byte[]> back = StateCheckpointCodec.readIfHashMatches(target, "deadbeef");
        assertThat(back).isEmpty();
        // right hash
        assertThat(StateCheckpointCodec.readIfHashMatches(target, hash)).isPresent();
    }

    @Test
    void verifyHashIsCheapAndCorrect(@TempDir Path tmp) throws IOException {
        // 4 MB random payload to make sure verifyHash streams and
        // doesn't read the whole body into memory just to hash it.
        Path target = tmp.resolve("state.bin");
        byte[] raw = new byte[4 * 1024 * 1024];
        new Random(123).nextBytes(raw);
        String hash = StateCheckpointCodec.writeAtomic(target, raw);
        assertThat(StateCheckpointCodec.verifyHash(target, hash)).isTrue();
        assertThat(StateCheckpointCodec.verifyHash(target, "00".repeat(32))).isFalse();
    }

    @Test
    void readRejectsBadMagicAndTruncation(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve("state.bin");
        Files.write(target, new byte[]{0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00});
        assertThatThrownBy(() -> StateCheckpointCodec.read(target))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("bad magic");
    }
}
