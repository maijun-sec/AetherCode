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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R403 — the DISK half of a checkpoint.
 *
 * <h2>★ WHAT THIS GATE HOLDS ★</h2>
 *
 * <ul>
 *   <li><b>The whole point round-trips.</b> The id, the kind, the
 *       transcript position, the user-turn ordinal, the summary AND every
 *       file's state — because a checkpoint that loses one of them on the
 *       way to disk is a checkpoint that comes back wrong.</li>
 *   <li><b>The three file states stay three.</b> "has content",
 *       "did not exist" (created) and "not restorable" are three
 *       DIFFERENT facts. Collapsing them loses the difference between
 *       "restore this file" and "delete this file" and "skip this
 *       file".</li>
 *   <li><b>A corrupt file costs ONE point, not the history.</b> A
 *       malformed metadata file is skipped; the rest still load.</li>
 *   <li><b>A missing blob is NOT an empty file.</b> The honest state is
 *       "not restorable" — reporting "" would restore an empty file over
 *       the user's code.</li>
 *   <li><b>The order is the metadata's, not the file name's.</b> The
 *       rewind menu numbers its rows by position in this list; sorting
 *       by a naming convention would mis-order a hand-minted
 *       checkpoint.</li>
 * </ul>
 */
class CheckpointDiskStoreR403Test {

    @TempDir Path root;

    private Path dir() {
        return root.resolve("checkpoints");
    }

    private static CodeCheckpointStore.Checkpoint point(
            String id, String kind, List<CodeCheckpointStore.FileState> files) {
        return new CodeCheckpointStore.Checkpoint(
                id, 42, 3, "a summary", kind, files);
    }

    // ------------------------------------------------------------------
    // the round trip
    // ------------------------------------------------------------------

    @Test
    void theWholePointRoundTrips() throws Exception {
        Path dir = dir();
        CheckpointDiskStore disk = new CheckpointDiskStore(dir);
        Path a = root.resolve("a.txt");
        Path b = root.resolve("b.bin");
        Path c = root.resolve("c.txt");
        Files.writeString(a, "hello\nworld\n", StandardCharsets.UTF_8);

        disk.save(point("cp-1", CodeCheckpointStore.KIND_TURN, List.of(
                new CodeCheckpointStore.FileState(a, "hello\nworld\n", false),
                // created: the file did not exist before the session
                new CodeCheckpointStore.FileState(b, null, true),
                // not restorable: binary / unreadable
                new CodeCheckpointStore.FileState(c, null, false))));

        List<CodeCheckpointStore.Checkpoint> loaded = disk.loadAll();
        assertEquals(1, loaded.size(), "exactly one point must come back: " + loaded);
        CodeCheckpointStore.Checkpoint cp = loaded.get(0);
        assertEquals("cp-1", cp.id());
        assertEquals(CodeCheckpointStore.KIND_TURN, cp.kind());
        assertEquals(42, cp.transcriptIndex());
        assertEquals(3, cp.userMessageIndex());
        assertEquals("a summary", cp.summary());
        assertEquals(3, cp.files().size());

        assertEquals("hello\nworld\n", cp.files().get(0).content());
        assertFalse(cp.files().get(0).created());
        assertTrue(cp.files().get(1).created(),
                "the 'created' flag must survive the round trip");
        assertNull(cp.files().get(1).content());
        assertFalse(cp.files().get(2).created());
        assertNull(cp.files().get(2).content());
    }

    @Test
    void theCancelKindRoundTrips() throws Exception {
        CheckpointDiskStore disk = new CheckpointDiskStore(dir());
        disk.save(point("cp-cancel-1", CodeCheckpointStore.KIND_CANCEL, List.of()));
        List<CodeCheckpointStore.Checkpoint> loaded = disk.loadAll();
        assertEquals(1, loaded.size());
        assertEquals(CodeCheckpointStore.KIND_CANCEL, loaded.get(0).kind());
        assertTrue(loaded.get(0).isCancelPoint(),
                "the cancel point must be recognisable as one");
    }

    @Test
    void aPointWithNoFilesRoundTrips() throws Exception {
        CheckpointDiskStore disk = new CheckpointDiskStore(dir());
        disk.save(point("cp-empty", CodeCheckpointStore.KIND_TURN, List.of()));
        List<CodeCheckpointStore.Checkpoint> loaded = disk.loadAll();
        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0).files().isEmpty());
    }

    @Test
    void loadAllOnAMissingDirectoryIsEmpty() {
        CheckpointDiskStore disk = new CheckpointDiskStore(root.resolve("never-created"));
        assertTrue(disk.loadAll().isEmpty(),
                "a missing directory is an empty history, not an error");
    }

    // ------------------------------------------------------------------
    // a corrupt file costs one point, not the history
    // ------------------------------------------------------------------

    @Test
    void aMalformedMetadataFileIsSkippedNotFatal() throws Exception {
        Path dir = dir();
        CheckpointDiskStore disk = new CheckpointDiskStore(dir);
        disk.save(point("cp-good", CodeCheckpointStore.KIND_TURN, List.of()));
        Files.writeString(dir.resolve("cp-broken.json"),
                "this is not json { {", StandardCharsets.UTF_8);

        List<CodeCheckpointStore.Checkpoint> loaded = disk.loadAll();
        assertEquals(1, loaded.size(),
                "the good point must survive the broken one: " + loaded);
        assertEquals("cp-good", loaded.get(0).id());
    }

    @Test
    void aMetadataFileWithoutAnIdIsSkipped() throws Exception {
        Path dir = dir();
        CheckpointDiskStore disk = new CheckpointDiskStore(dir);
        disk.save(point("cp-good", CodeCheckpointStore.KIND_TURN, List.of()));
        Files.writeString(dir.resolve("cp-noid.json"),
                "{\"kind\":\"turn\",\"files\":[]}", StandardCharsets.UTF_8);

        List<CodeCheckpointStore.Checkpoint> loaded = disk.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("cp-good", loaded.get(0).id());
    }

    @Test
    void aMissingBlobMakesTheFileUnrestorable_notEmpty() throws Exception {
        Path dir = dir();
        CheckpointDiskStore disk = new CheckpointDiskStore(dir);
        Path a = root.resolve("a.txt");
        disk.save(point("cp-1", CodeCheckpointStore.KIND_TURN, List.of(
                new CodeCheckpointStore.FileState(a, "real content", false))));

        // The blob goes missing (a partial disk wipe, an antivirus
        // quarantine, a user cleaning the directory).
        try (var walk = Files.walk(dir)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) { } });
        }

        List<CodeCheckpointStore.Checkpoint> loaded = disk.loadAll();
        assertEquals(1, loaded.size());
        CodeCheckpointStore.FileState fs = loaded.get(0).files().get(0);
        assertNull(fs.content(),
                "a missing blob is NOT an empty file — restoring \"\" would wipe the user's code");
        assertFalse(fs.created(),
                "the file was not created by the session — it must not be deleted on restore");
    }

    // ------------------------------------------------------------------
    // ordering
    // ------------------------------------------------------------------

    @Test
    void theOrderIsTheMetadatasNotTheFileNames() throws Exception {
        Path dir = dir();
        Files.createDirectories(dir);
        // "cp-zzz" sorts LAST by name but was written FIRST.
        writeMeta(dir, "cp-zzz", 1000L);
        writeMeta(dir, "cp-aaa", 2000L);

        CheckpointDiskStore disk = new CheckpointDiskStore(dir);
        List<CodeCheckpointStore.Checkpoint> loaded = disk.loadAll();
        assertEquals(2, loaded.size());
        assertEquals("cp-zzz", loaded.get(0).id(),
                "the OLDER point comes first — the menu numbers rows by this order");
        assertEquals("cp-aaa", loaded.get(1).id());
    }

    /** Write a metadata file by hand, with an explicit creation time. */
    private static void writeMeta(Path dir, String id, long createdAt) throws Exception {
        String json = "{\"id\":\"" + id + "\",\"kind\":\"turn\","
                + "\"transcriptIndex\":1,\"userMessageIndex\":0,"
                + "\"summary\":\"s\",\"createdAt\":" + createdAt + ",\"files\":[]}";
        Files.writeString(dir.resolve(id + ".json"), json, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // pruning, deleting, the counter
    // ------------------------------------------------------------------

    @Test
    void pruneKeepsTheNewest() throws Exception {
        Path dir = dir();
        Files.createDirectories(dir);
        writeMeta(dir, "cp-1", 1000L);
        writeMeta(dir, "cp-2", 2000L);
        writeMeta(dir, "cp-3", 3000L);

        CheckpointDiskStore disk = new CheckpointDiskStore(dir);
        int deleted = disk.prune(2);
        assertEquals(1, deleted);
        List<CodeCheckpointStore.Checkpoint> left = disk.loadAll();
        assertEquals(2, left.size());
        assertEquals("cp-2", left.get(0).id());
        assertEquals("cp-3", left.get(1).id());
    }

    @Test
    void deleteRemovesTheMetadataAndTheBlobs() throws Exception {
        Path dir = dir();
        CheckpointDiskStore disk = new CheckpointDiskStore(dir);
        Path a = root.resolve("a.txt");
        disk.save(point("cp-1", CodeCheckpointStore.KIND_TURN, List.of(
                new CodeCheckpointStore.FileState(a, "content", false))));
        assertNotNull(Files.readString(dir.resolve("cp-1").resolve("0.txt")));

        disk.delete("cp-1");
        assertFalse(Files.exists(dir.resolve("cp-1.json")),
                "the metadata must be gone");
        assertFalse(Files.exists(dir.resolve("cp-1")),
                "the blob directory must be gone");
        assertTrue(disk.loadAll().isEmpty());
    }

    @Test
    void deleteAllEmptiesTheDirectory() throws Exception {
        Path dir = dir();
        CheckpointDiskStore disk = new CheckpointDiskStore(dir);
        disk.save(point("cp-1", CodeCheckpointStore.KIND_TURN, List.of()));
        disk.save(point("cp-2", CodeCheckpointStore.KIND_TURN, List.of()));
        disk.deleteAll();
        assertTrue(disk.loadAll().isEmpty());
    }

    @Test
    void theEvictedCounterRoundTrips() {
        CheckpointDiskStore disk = new CheckpointDiskStore(dir());
        assertEquals(0L, disk.readEvicted(),
                "a fresh directory has evicted nothing");
        disk.writeEvicted(12L);
        assertEquals(12L, disk.readEvicted());
    }

    @Test
    void aMalformedCounterIsZeroNotAnException() throws Exception {
        Path dir = dir();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(CheckpointDiskStore.STATE_FILE),
                "not json", StandardCharsets.UTF_8);
        assertEquals(0L, new CheckpointDiskStore(dir).readEvicted());
    }

    @Test
    void aMetadataFileWithAPathTraversalIdIsSkipped() throws Exception {
        Path dir = dir();
        Files.createDirectories(dir);
        writeMeta(dir, "cp-ok", 1000L);
        // The FILE name is safe; the ID INSIDE it is not. The id is
        // resolved as a directory name when the blobs are read, so an
        // id carrying a path separator would steer the read outside
        // this directory.
        Files.writeString(dir.resolve("cp-evil.json"),
                "{\"id\":\"../../evil\",\"kind\":\"turn\",\"transcriptIndex\":1,"
                        + "\"userMessageIndex\":0,\"summary\":\"s\",\"createdAt\":2000,"
                        + "\"files\":[]}", StandardCharsets.UTF_8);

        List<CodeCheckpointStore.Checkpoint> loaded =
                new CheckpointDiskStore(dir).loadAll();
        assertEquals(1, loaded.size(),
                "the unusable id must be skipped, the good point kept: " + loaded);
        assertEquals("cp-ok", loaded.get(0).id());
    }
}
