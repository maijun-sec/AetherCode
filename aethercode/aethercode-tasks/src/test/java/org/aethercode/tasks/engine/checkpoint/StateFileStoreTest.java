package org.aethercode.tasks.engine.checkpoint;

import org.aethercode.tasks.engine.core.SupervisorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-1-02: 4 tests for {@link StateFileStore}. Covers the
 * off-process layout, hash-mismatch read, auto-eviction at
 * 100 MB, and integration with {@link SupervisorStore}.
 */
class StateFileStoreTest {

    @Test
    void writeAndReadRoundTrips(@TempDir Path tmp) throws IOException {
        StateFileStore store = new StateFileStore(tmp.resolve("sessions")).init();
        byte[] raw = "state-payload".getBytes();
        String hash = store.write("s1", raw);
        assertThat(hash).isEqualTo(StateCheckpointCodec.sha256Hex(raw));
        Optional<byte[]> back = store.read("s1", hash);
        assertThat(back).isPresent();
        assertThat(back.get()).isEqualTo(raw);
    }

    @Test
    void readReturnsEmptyOnMissingSession(@TempDir Path tmp) throws IOException {
        StateFileStore store = new StateFileStore(tmp.resolve("sessions")).init();
        assertThat(store.read("nope", null)).isEmpty();
    }

    @Test
    void evictionPrunesOldestUntilUnderCap(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("sessions");
        // 200 KB cap so we can hit it with a handful of small files.
        StateFileStore store = new StateFileStore(root, 200 * 1024);

        // Each file is 80 KB; the third write should trigger eviction.
        byte[] payload = new byte[80 * 1024];
        new Random(7).nextBytes(payload);
        store.write("s1", payload);
        Thread.sleep(5); // ensure mtime ordering
        store.write("s2", payload);
        Thread.sleep(5);
        store.write("s3", payload);
        // s1 is the oldest; should be pruned, s2 + s3 should remain.
        long total = store.totalBytes();
        assertThat(total).isLessThanOrEqualTo(200 * 1024);
        assertThat(store.read("s1", null)).isEmpty();
        assertThat(store.read("s2", null)).isPresent();
        assertThat(store.read("s3", null)).isPresent();
    }

    @Test
    void writeUpdatesSupervisorStoreHashAndSize(@TempDir Path tmp) throws IOException {
        SupervisorStore sup = new SupervisorStore();
        String sid = sup.createChild(tmp.toString(), "p", null, null);
        StateFileStore store = new StateFileStore(tmp.resolve("sessions"),
                StateFileStore.DEFAULT_MAX_TOTAL_BYTES, sup);
        store.init();
        byte[] raw = "abc".getBytes();
        String hash = store.write(sid, raw);
        SupervisorStore.StatePointer p = sup.getStatePointer(sid).orElseThrow();
        assertThat(p.hash()).isEqualTo(hash);
        assertThat(p.sizeBytes()).isEqualTo(raw.length);
        // delete also clears the pointer
        assertThat(store.delete(sid)).isTrue();
        assertThat(sup.getStatePointer(sid)).isEmpty();
        assertThat(Files.exists(tmp.resolve("sessions").resolve(sid).resolve("state.bin"))).isFalse();
    }
}
