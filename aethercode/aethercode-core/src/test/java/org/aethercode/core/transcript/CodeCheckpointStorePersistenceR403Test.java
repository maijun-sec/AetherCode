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

package org.aethercode.core.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R403 — the STORE's half of the persistence contract.
 *
 * <h2>★ WHAT THIS GATE HOLDS ★</h2>
 *
 * <ul>
 *   <li><b>A point survives a restart.</b> A second store pointed at the
 *       same directory finds the point the first store wrote — that is
 *       the whole point of R403 (gap G1: the history used to die with
 *       the daemon).</li>
 *   <li><b>The DISK evicts with the MEMORY.</b> If the disk kept a
 *       different number of points than the memory, a restart would
 *       resurrect a point the user was told had been dropped — or lose
 *       one they were still looking at.</li>
 *   <li><b>The eviction counter survives a restart.</b> A counter that
 *       resets to zero claims "nothing was lost" when twelve points
 *       are in fact gone.</li>
 *   <li><b>Clear forgets the disk too.</b> A "clear" that un-clears
 *       itself on the next load is not a clear.</li>
 * </ul>
 */
class CodeCheckpointStorePersistenceR403Test {

    @TempDir Path root;

    private Path dir() {
        return root.resolve("checkpoints");
    }

    /** A transcript that says "the session wrote this file". */
    private static List<Message> wroteFile(String path) {
        return List.of(new Message(
                "wrote-" + path,
                Role.ASSISTANT,
                List.of(new ContentBlock.ToolUseBlock(
                        "tu-" + path, "file_write", java.util.Map.of("file_path", path))),
                null,
                java.util.Map.of()));
    }

    @Test
    void aPointSurvivesANewStore() throws Exception {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "ORIGINAL", StandardCharsets.UTF_8);

        CodeCheckpointStore first = new CodeCheckpointStore(dir());
        first.save("cp-1", wroteFile(f.toString()), 7, 0, root, "turn one");
        assertEquals(1, first.size());

        // ★ THE RESTART ★ A brand-new store on the same directory.
        CodeCheckpointStore second = new CodeCheckpointStore(dir());
        assertEquals(1, second.loadFromDisk(), "the point must be on disk");
        assertEquals(1, second.size());
        var cp = second.get("cp-1");
        assertTrue(cp.isPresent(), "the point must be findable by id: " + second.list());
        assertEquals(7, cp.get().transcriptIndex(),
                "the transcript position must survive — it is the anchor");
        assertEquals("ORIGINAL", cp.get().files().get(0).content());
    }

    @Test
    void aRestoredPointStillRestoresTheFile() throws Exception {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "ORIGINAL", StandardCharsets.UTF_8);

        CodeCheckpointStore first = new CodeCheckpointStore(dir());
        first.save("cp-1", wroteFile(f.toString()), 7, 0, root, "turn one");
        Files.writeString(f, "MODIFIED", StandardCharsets.UTF_8);

        CodeCheckpointStore second = new CodeCheckpointStore(dir());
        second.loadFromDisk();
        var result = second.restore(second.get("cp-1").orElseThrow());
        assertEquals(1, result.restored().size(), "the file must be restored");
        assertEquals("ORIGINAL", Files.readString(f, StandardCharsets.UTF_8),
                "a point that came back from disk must still restore the file");
    }

    @Test
    void anInMemoryStoreWritesNothing() {
        CodeCheckpointStore store = new CodeCheckpointStore();
        store.save("cp-1", List.of(), 0, 0, root, "turn one");
        assertEquals(1, store.size());
        assertFalse(Files.exists(root.resolve("checkpoints")),
                "the no-arg constructor must not touch the disk");
        assertNull(store.disk(),
                "the no-arg constructor has no disk store — persistence is off");
    }

    @Test
    void theEvictionDeletesFromDiskToo() {
        CodeCheckpointStore store = new CodeCheckpointStore(dir());
        for (int i = 0; i < CodeCheckpointStore.MAX_CHECKPOINTS + 3; i++) {
            store.save("cp-" + i, List.of(), i, i, root, "point " + i);
        }
        assertEquals(CodeCheckpointStore.MAX_CHECKPOINTS, store.size());

        // ★ THE DISK MUST AGREE ★ The disk holds the same number of
        // points as the memory — not more (a resurrected point), not
        // fewer (a point the user is looking at that vanishes).
        CheckpointDiskStore disk = new CheckpointDiskStore(dir());
        assertEquals(CodeCheckpointStore.MAX_CHECKPOINTS, disk.loadAll().size(),
                "the disk and the memory must hold the same number of points");
        assertFalse(disk.loadAll().stream().anyMatch(c -> c.id().equals("cp-0")),
                "the oldest point must be gone from the disk too");
    }

    @Test
    void theEvictionCounterSurvivesARestart() {
        CodeCheckpointStore store = new CodeCheckpointStore(dir());
        for (int i = 0; i < CodeCheckpointStore.MAX_CHECKPOINTS + 1; i++) {
            store.save("cp-" + i, List.of(), i, i, root, "point " + i);
        }
        assertEquals(1L, store.evictedCount());

        CodeCheckpointStore restarted = new CodeCheckpointStore(dir());
        assertEquals(1L, restarted.evictedCount(),
                "a restart that reports zero claims nothing was lost — a lie");
    }

    @Test
    void clearForgetsTheDiskToo() {
        CodeCheckpointStore store = new CodeCheckpointStore(dir());
        store.save("cp-1", List.of(), 0, 0, root, "one");
        assertEquals(1, new CheckpointDiskStore(dir()).loadAll().size());

        store.clear();
        assertEquals(0, store.size());
        assertEquals(0, new CheckpointDiskStore(dir()).loadAll().size(),
                "a clear that un-clears itself on the next load is not a clear");
    }

    @Test
    void theEvictionCounterSurvivesAClear() {
        CodeCheckpointStore store = new CodeCheckpointStore(dir());
        for (int i = 0; i < CodeCheckpointStore.MAX_CHECKPOINTS + 1; i++) {
            store.save("cp-" + i, List.of(), i, i, root, "point " + i);
        }
        assertEquals(1L, store.evictedCount());

        store.clear();
        assertEquals(1L, store.evictedCount(),
                "a clear does not un-lose the points that were dropped");
        // ★ AND THE DISK MUST AGREE ★ The next restart reads the
        // counter from the disk; if the clear dropped it there, the
        // restart would report zero — a different number than the one
        // the user was just shown.
        assertEquals(1L, new CodeCheckpointStore(dir()).evictedCount(),
                "the disk's counter must survive the clear too");
    }

    @Test
    void theKindRoundTripsThroughDisk() {
        CodeCheckpointStore store = new CodeCheckpointStore(dir());
        store.save("cp-turn", List.of(), 0, 0, root, "turn",
                CodeCheckpointStore.KIND_TURN);
        store.save("cp-cancel", List.of(), 0, 0, root, "stopped",
                CodeCheckpointStore.KIND_CANCEL);

        CodeCheckpointStore restarted = new CodeCheckpointStore(dir());
        restarted.loadFromDisk();
        assertEquals(CodeCheckpointStore.KIND_TURN,
                restarted.get("cp-turn").orElseThrow().kind());
        assertEquals(CodeCheckpointStore.KIND_CANCEL,
                restarted.get("cp-cancel").orElseThrow().kind());
        assertTrue(restarted.get("cp-cancel").orElseThrow().isCancelPoint());
    }

    @Test
    void aStoreWithNoKindDefaultsToATurnPoint() {
        CodeCheckpointStore store = new CodeCheckpointStore(dir());
        var cp = store.save("cp-1", List.of(), 0, 0, root, "one");
        assertEquals(CodeCheckpointStore.KIND_TURN, cp.kind(),
                "a point minted without a kind is a turn point");
    }
}
