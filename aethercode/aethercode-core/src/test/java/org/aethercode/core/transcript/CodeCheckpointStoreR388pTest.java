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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R388p — the checkpoint store must be BOUNDED.
 *
 * <p>★ WHY THIS MATTERS ★
 *
 * <p>A checkpoint holds a FULL COPY of the content of every file the
 * session has touched so far. So the memory a session's checkpoints
 * take is
 *
 * <pre>
 *   turns x files-touched x file-size
 * </pre>
 *
 * <p>— quadratic in the session's length. Before R388p the store was a
 * plain {@code LinkedHashMap} with no eviction, so a long session grew
 * the heap without bound and the daemon would eventually die of
 * {@code OutOfMemoryError} rather than of anything the user could see.
 */
class CodeCheckpointStoreR388pTest {

    /**
     * A transcript that wrote one file, so each checkpoint has content
     * to hold.
     */
    private static List<Message> transcriptWith(Path file) {
        Message write = new Message(
                "a-1",
                Role.ASSISTANT,
                List.of(
                        new ContentBlock.ToolUseBlock(
                                "tu-1", "file_write", java.util.Map.of("file_path", file.toString())),
                        new ContentBlock.TextBlock("writing")),
                null,
                java.util.Map.of());
        return List.of(Message.userText("turn one"), write, Message.assistantText("done"));
    }

    @Test
    @DisplayName("the store never grows past its bound")
    void theStoreNeverGrowsPastItsBound(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "content", StandardCharsets.UTF_8);

        CodeCheckpointStore store = new CodeCheckpointStore();
        int over = CodeCheckpointStore.MAX_CHECKPOINTS + 10;
        for (int i = 0; i < over; i++) {
            store.save("cp-" + i, transcriptWith(file), 3, i, dir, "turn " + i);
        }
        assertEquals(
                CodeCheckpointStore.MAX_CHECKPOINTS,
                store.size(),
                "the store must hold at most MAX_CHECKPOINTS — each one holds a full copy of every touched file");
        assertEquals(
                over - CodeCheckpointStore.MAX_CHECKPOINTS,
                store.evictedCount(),
                "the store must REPORT how many checkpoints it dropped");
    }

    @Test
    @DisplayName("the eviction is FIFO — the OLDEST goes first")
    void theEvictionIsFifo(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "content", StandardCharsets.UTF_8);

        CodeCheckpointStore store = new CodeCheckpointStore();
        int over = CodeCheckpointStore.MAX_CHECKPOINTS + 5;
        for (int i = 0; i < over; i++) {
            store.save("cp-" + i, transcriptWith(file), 3, i, dir, "turn " + i);
        }
        // The OLDEST are gone.
        assertTrue(store.get("cp-0").isEmpty(), "the oldest checkpoint must be evicted first");
        assertTrue(store.get("cp-4").isEmpty(), "the oldest checkpoints must be evicted first");
        // The NEWEST survive — those are the ones a user rewinds to.
        assertNotNull(store.get("cp-" + (over - 1)), "the newest checkpoint must survive");
        assertNotNull(store.get("cp-" + (over - CodeCheckpointStore.MAX_CHECKPOINTS)), "the newest checkpoint must survive");
    }

    @Test
    @DisplayName("the store's memory does NOT grow with the number of turns")
    void theStoresMemoryDoesNotGrowWithTheNumberOfTurns(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "content", StandardCharsets.UTF_8);

        CodeCheckpointStore store = new CodeCheckpointStore();
        // Twice the bound, so the second half is entirely evicted.
        for (int i = 0; i < CodeCheckpointStore.MAX_CHECKPOINTS * 2; i++) {
            store.save("cp-" + i, transcriptWith(file), 3, i, dir, "turn " + i);
        }
        // The held CONTENT is bounded by the bound, not by the turns.
        long heldBytes = store.list().stream()
                .mapToLong(cp -> cp.files().stream()
                        .mapToLong(f -> f.content() == null ? 0L : f.content().length())
                        .sum())
                .sum();
        long oneCheckpoint = store.list().get(0).files().stream()
                .mapToLong(f -> f.content() == null ? 0L : f.content().length())
                .sum();
        assertTrue(
                heldBytes <= oneCheckpoint * CodeCheckpointStore.MAX_CHECKPOINTS,
                "the store holds " + heldBytes + " bytes of file content — that is more than "
                        + CodeCheckpointStore.MAX_CHECKPOINTS + " checkpoints' worth");
    }

    @Test
    @DisplayName("an evicted checkpoint can no longer be restored")
    void anEvictedCheckpointCanNoLongerBeRestored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "content", StandardCharsets.UTF_8);

        CodeCheckpointStore store = new CodeCheckpointStore();
        for (int i = 0; i < CodeCheckpointStore.MAX_CHECKPOINTS + 1; i++) {
            store.save("cp-" + i, transcriptWith(file), 3, i, dir, "turn " + i);
        }
        assertTrue(
                store.get("cp-0").isEmpty(),
                "an evicted checkpoint must be gone from the store, not merely hidden");
        // And the surviving ones still restore.
        var last = store.get("cp-" + CodeCheckpointStore.MAX_CHECKPOINTS);
        assertTrue(last.isPresent(), "the newest checkpoint must survive the eviction");
        Files.writeString(file, "changed", StandardCharsets.UTF_8);
        var result = store.restore(last.get());
        assertEquals(List.of("a.txt"), result.restored(), "the surviving checkpoint must still restore");
    }

    @Test
    @DisplayName("the eviction count survives a clear")
    void theEvictionCountSurvivesAClear(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "content", StandardCharsets.UTF_8);

        CodeCheckpointStore store = new CodeCheckpointStore();
        for (int i = 0; i < CodeCheckpointStore.MAX_CHECKPOINTS + 3; i++) {
            store.save("cp-" + i, transcriptWith(file), 3, i, dir, "turn " + i);
        }
        long evicted = store.evictedCount();
        assertTrue(evicted > 0, "the store must have evicted something");
        store.clear();
        assertEquals(0, store.size(), "clear must empty the store");
        assertEquals(
                evicted,
                store.evictedCount(),
                "the eviction count must survive a clear — the memory was freed and the user still cannot go back to those points");
        assertFalse(store.list().iterator().hasNext(), "a cleared store must list nothing");
    }
}
