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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R403: the DISK half of a code checkpoint.
 *
 * <p><b>Why this class exists.</b> Before R403 a checkpoint lived in a
 * {@link CodeCheckpointStore} map inside the engine instance
 * ({@code AetherCodeEngine.java:2824-2825}) and <b>nowhere else</b> — so a
 * daemon restart erased the whole history: not just the file snapshots, but
 * also the anchor that said <em>where in the conversation</em> the snapshot
 * was taken. That is gap G1 in
 * {@code docs/R400-cancel-intent-checkpoint-design.md}.
 *
 * <p><b>What this class is, and what it is not.</b> It is the ON-DISK
 * FORMAT plus the IO for it. It does not know what a checkpoint <em>means</em>
 * — that definition lives in {@link CodeCheckpointStore}, and this class
 * round-trips whatever it is handed. Two classes that both defined
 * "a checkpoint" would be two truths, and the second one would win at runtime.
 *
 * <p><b>The layout.</b> One directory per session:
 *
 * <pre>
 *   &lt;sessionsDir&gt;/&lt;sessionId&gt;/checkpoints/
 *     cp-3-1734567890123.json      ← the metadata
 *     cp-3-1734567890123/          ← the files' BEFORE content
 *       0.txt
 *       1.txt
 *     state.json                   ← {"evicted": N}
 * </pre>
 *
 * <p><b>Why the content is not in the JSON.</b> A checkpoint holds the full
 * text of every file the session touched. Putting that inside the metadata
 * would make every metadata file as large as the working set, and would make
 * the JSON the one thing that has to be parsed to answer "which files does
 * this point cover?" — a question the metadata alone answers. The blobs are
 * separate files so the metadata stays small and readable.
 *
 * <p><b>Why three flags per file and not one.</b> They encode three
 * DIFFERENT facts, and collapsing them loses information the restore needs:
 *
 * <ul>
 *   <li>{@code created=true} — the file did NOT exist before the session
 *       touched it ⇒ the restore DELETES it. Writing it back as an empty
 *       file would leave a zero-byte file where the user's tree had none.</li>
 *   <li>{@code restorable=false} — the content was not captured (binary, or
 *       unreadable) ⇒ the restore SKIPS it and reports it, rather than
 *       writing mangled bytes back.</li>
 *   <li>{@code blob=null} — there is no content to store. That is the case
 *       for both of the above, but for different reasons, so the two flags
 *       are separate.</li>
 * </ul>
 *
 * <p><b>Why the whole point is written as ONE record.</b> The files list, the
 * transcript position and the content are saved together, deliberately. The
 * old derivation ("which files did the session touch?" ⇒ scan the
 * transcript's tool_use blocks) is only valid while the transcript is the
 * one that was live at the time. After a restart — or after a compaction
 * rewrote the transcript — that derivation returns a DIFFERENT set, and a
 * restore would then silently restore the wrong files. Saving the set
 * alongside the content is what makes the checkpoint self-contained.
 *
 * <p><b>Never throws on a bad file.</b> A malformed JSON, a missing blob, an
 * unreadable directory — each is skipped and logged, and the other
 * checkpoints still load. One corrupt file must not cost the user the whole
 * history.
 */
public final class CheckpointDiskStore {

    private static final Logger LOG = LoggerFactory.getLogger(CheckpointDiskStore.class);

    /** The per-session sub-directory name. */
    public static final String DIR_NAME = "checkpoints";

    /** The file that holds the eviction counter. */
    public static final String STATE_FILE = "state.json";

    /** The metadata file suffix. */
    public static final String META_SUFFIX = ".json";

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param dir the directory that holds this session's checkpoints
     *            (typically {@code <sessionsDir>/<sessionId>/checkpoints})
     */
    public CheckpointDiskStore(Path dir) {
        this.dir = dir;
    }

    /** The directory this store reads and writes. */
    public Path dir() {
        return dir;
    }

    /**
     * Write one checkpoint to disk.
     *
     * <p>★ THE ORDER MATTERS ★ The blobs are written FIRST and the metadata
     * LAST, and the metadata lands via an atomic rename. So a crash in the
     * middle leaves either "a directory with no metadata" (ignored on load —
     * the point simply never existed) or "a complete point" — never "a
     * metadata file that points at blobs which are not there".
     *
     * @param cp the checkpoint to persist
     * @throws IOException when the directory or a blob cannot be written
     */
    public void save(CodeCheckpointStore.Checkpoint cp) throws IOException {
        if (cp == null || cp.id() == null || cp.id().isBlank()) {
            throw new IllegalArgumentException("checkpoint id is required");
        }
        Files.createDirectories(dir);
        Path blobDir = dir.resolve(cp.id());
        Files.createDirectories(blobDir);

        ArrayNode files = mapper.createArrayNode();
        int index = 0;
        for (CodeCheckpointStore.FileState fs : cp.files()) {
            ObjectNode f = mapper.createObjectNode();
            f.put("path", fs.path().toString());
            f.put("created", fs.created());
            // ★ TWO FLAGS, TWO FACTS ★ A created file has no content by
            // definition (it did not exist), but the restore still has to
            // DELETE it — so it is restorable. A binary file has no
            // content and nothing the restore can do about it — so it is
            // not.
            boolean restorable = fs.created() || fs.content() != null;
            f.put("restorable", restorable);
            if (fs.content() != null) {
                String blobName = index + ".txt";
                Files.writeString(blobDir.resolve(blobName), fs.content(),
                        StandardCharsets.UTF_8);
                f.put("blob", blobName);
            } else {
                f.putNull("blob");
            }
            files.add(f);
            index++;
        }

        ObjectNode meta = mapper.createObjectNode();
        meta.put("id", cp.id());
        meta.put("kind", cp.kind() == null ? CodeCheckpointStore.KIND_TURN : cp.kind());
        meta.put("transcriptIndex", cp.transcriptIndex());
        meta.put("userMessageIndex", cp.userMessageIndex());
        meta.put("summary", cp.summary() == null ? "" : cp.summary());
        meta.put("createdAt", System.currentTimeMillis());
        meta.set("files", files);

        // ★ ATOMIC ★ The metadata is the point's entry into the world; it
        // must not be observed half-written.
        Path metaFile = dir.resolve(cp.id() + META_SUFFIX);
        Path tmp = dir.resolve(cp.id() + META_SUFFIX + ".tmp");
        Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(meta), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, metaFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
            Files.move(tmp, metaFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Every persisted checkpoint, OLDEST FIRST.
     *
     * <p>Ordering matters: the rewind menu numbers its rows by position in
     * this list, and the restore path resolves the same number back through
     * it. Two different orders would put a "restore point 3" one message off.
     *
     * @return the checkpoints, oldest first; empty when the directory does
     *         not exist
     */
    public List<CodeCheckpointStore.Checkpoint> loadAll() {
        List<CodeCheckpointStore.Checkpoint> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        List<Path> metas = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(META_SUFFIX))
                    .forEach(metas::add);
        } catch (IOException e) {
            LOG.warn("R403: cannot list {}: {}", dir, e.getMessage());
            return out;
        }
        // ★ THE ORDER IS THE METADATA'S, NOT THE FILENAME'S ★ The
        // filename carries the creation time in its millisecond field
        // ("cp-<ordinal>-<millis>"), but that is a NAMING CONVENTION —
        // the metadata's createdAt is the fact. Sorting by the
        // convention would silently mis-order a checkpoint whose id was
        // minted by hand.
        List<Loaded> loaded = new ArrayList<>();
        for (Path meta : metas) {
            Loaded one = readOne(meta);
            if (one != null) loaded.add(one);
        }
        loaded.sort(Comparator.comparingLong(Loaded::createdAt));
        for (Loaded one : loaded) out.add(one.checkpoint());
        return out;
    }

    /**
     * A checkpoint together with the creation time read from its
     * metadata. The time is a property of the FILE, not of the point, so
     * it is carried beside the record rather than inside it.
     */
    private record Loaded(CodeCheckpointStore.Checkpoint checkpoint, long createdAt) { }

    /**
     * Read one metadata file. Returns null when it is unreadable or
     * malformed — the caller skips it and the rest still loads.
     */
    private Loaded readOne(Path meta) {
        try {
            JsonNode root = mapper.readTree(Files.readString(meta, StandardCharsets.UTF_8));
            JsonNode idNode = root.get("id");
            if (idNode == null || idNode.asText("").isBlank()) {
                LOG.warn("R403: checkpoint metadata without an id: {}", meta);
                return null;
            }
            String id = idNode.asText();
            // ★ THE ID IS A DIRECTORY NAME ★ The blob directory is
            // resolved FROM the id, so an id carrying a path separator
            // would make the reader look outside this directory. The
            // ids are engine-minted, but the metadata is a FILE — a
            // hand-edited (or corrupted) one must not be able to steer
            // the read somewhere else.
            if (!isSafeId(id)) {
                LOG.warn("R403: skipping a checkpoint with an unusable id: {}", id);
                return null;
            }
            String kind = root.hasNonNull("kind") ? root.get("kind").asText()
                    : CodeCheckpointStore.KIND_TURN;
            int transcriptIndex = root.path("transcriptIndex").asInt(0);
            int userMessageIndex = root.path("userMessageIndex").asInt(0);
            String summary = root.path("summary").asText("");
            long created = root.path("createdAt").asLong(0L);

            List<CodeCheckpointStore.FileState> files = new ArrayList<>();
            JsonNode filesNode = root.get("files");
            if (filesNode != null && filesNode.isArray()) {
                for (JsonNode f : filesNode) {
                    String path = f.path("path").asText("");
                    if (path.isBlank()) continue;
                    boolean created0 = f.path("created").asBoolean(false);
                    String blob = f.hasNonNull("blob") ? f.get("blob").asText() : null;
                    String content = null;
                    if (blob != null && !blob.isBlank()) {
                        Path blobFile = dir.resolve(id).resolve(blob);
                        try {
                            content = Files.readString(blobFile, StandardCharsets.UTF_8);
                        } catch (IOException ioe) {
                            // ★ THE BLOB IS GONE ★ The metadata says this
                            // file had content; the content is not there.
                            // The honest state is "not restorable" — NOT
                            // "restores to an empty file".
                            LOG.warn("R403: checkpoint {} is missing blob {}: {}",
                                    id, blob, ioe.getMessage());
                            content = null;
                        }
                    }
                    files.add(new CodeCheckpointStore.FileState(
                            Path.of(path), content, created0));
                }
            }
            return new Loaded(new CodeCheckpointStore.Checkpoint(
                    id, transcriptIndex, userMessageIndex, summary, kind, files), created);
        } catch (IOException | RuntimeException e) {
            LOG.warn("R403: skipping unreadable checkpoint metadata {}: {}",
                    meta, e.getMessage());
            return null;
        }
    }

    /**
     * Is this id usable as a file / directory name inside the
     * checkpoint directory?
     *
     * <p>Only letters, digits, dot, dash and underscore — so the id
     * cannot name a parent directory, a sibling directory, or an
     * absolute path.
     */
    private static boolean isSafeId(String id) {
        if (id == null || id.isBlank() || id.length() > 200) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!ok) return false;
        }
        // "." and ".." are dot-only names — they name directories, not
        // checkpoints.
        return !id.equals(".") && !id.equals("..");
    }

    /**
     * Delete one checkpoint (metadata + blobs). Missing files are not an
     * error — the goal is "it is gone".
     */
    public void delete(String id) {
        if (id == null || id.isBlank()) return;
        try {
            Files.deleteIfExists(dir.resolve(id + META_SUFFIX));
            deleteRecursively(dir.resolve(id));
        } catch (IOException | RuntimeException e) {
            LOG.warn("R403: could not delete checkpoint {}: {}", id, e.getMessage());
        }
    }

    /**
     * Keep only the newest {@code keepNewest} checkpoints; delete the rest.
     *
     * <p>★ THE MEMORY STORE MUST USE THE SAME RULE ★ If the disk kept a
     * different number than the memory, a restart would load a different
     * list than the one the user was just looking at — the menu would
     * "gain" rows the user never saw.
     *
     * @param keepNewest how many to keep
     * @return how many were deleted
     */
    public int prune(int keepNewest) {
        List<CodeCheckpointStore.Checkpoint> all = loadAll();
        if (all.size() <= keepNewest) return 0;
        int deleted = 0;
        for (int i = 0; i < all.size() - keepNewest; i++) {
            delete(all.get(i).id());
            deleted++;
        }
        return deleted;
    }

    /**
     * Delete every checkpoint in this directory.
     *
     * <p>★ THE EVICTION COUNTER SURVIVES ★ It lives in
     * {@code state.json}, which this method does NOT touch. The counter
     * says "how many points this session has lost" — a clear does not
     * un-lose them. Deleting the counter with the points would make
     * the next restart report zero, i.e. claim that nothing was ever
     * dropped.
     */
    public void deleteAll() {
        for (CodeCheckpointStore.Checkpoint cp : loadAll()) {
            delete(cp.id());
        }
    }

    /**
     * The number of checkpoints that were evicted (dropped because the
     * store was full) — persisted so a restart does not report "nothing
     * was lost" when in fact twelve points are gone.
     */
    public long readEvicted() {
        Path file = dir.resolve(STATE_FILE);
        if (!Files.exists(file)) return 0L;
        try {
            JsonNode root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return root.path("evicted").asLong(0L);
        } catch (IOException | RuntimeException e) {
            LOG.warn("R403: cannot read {}: {}", file, e.getMessage());
            return 0L;
        }
    }

    /** Persist the eviction counter. Best-effort. */
    public void writeEvicted(long count) {
        try {
            Files.createDirectories(dir);
            ObjectNode state = mapper.createObjectNode();
            state.put("evicted", count);
            Files.writeString(dir.resolve(STATE_FILE),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(state),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOG.warn("R403: cannot write the eviction counter: {}", e.getMessage());
        }
    }

    /** Delete a directory and everything under it. */
    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
