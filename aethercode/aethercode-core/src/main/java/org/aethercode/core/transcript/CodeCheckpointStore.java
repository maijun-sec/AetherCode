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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;

/**
 * R388n: the CODE side of a checkpoint — the files, not just the
 * conversation.
 *
 * <p><b>Why this exists.</b> Claude Code's rewind menu offers two
 * distinct restores:
 *
 * <ul>
 *   <li><b>Restore conversation</b> — roll the transcript back. The
 *       daemon already had that ({@code rewind}).</li>
 *   <li><b>Restore code</b> — put the FILES back the way they were.
 *       The daemon had NOTHING for this: {@link CheckpointStore} (the
 *       pre-existing class) stores {@code List<Message>} and nothing
 *       else, and it is dead code — no production reference.</li>
 * </ul>
 *
 * <p><b>Why the files are snapshotted, and not the git tree.</b>
 * A git-based restore would need a commit per turn (or a stash), and
 * it would restore files the MODEL never touched — including the
 * user's own uncommitted work, which is the one thing a user least
 * wants a "restore" to throw away. This store snapshots <b>only the
 * files the tools wrote</b>, so the restore is exactly scoped to what
 * the session did.
 *
 * <p><b>What a checkpoint holds.</b> For each file the session touched:
 *
 * <ul>
 *   <li>the content <b>before</b> the session touched it, and</li>
 *   <li>whether the file <b>existed</b> before — a file the session
 *       CREATED must be DELETED on restore, not written back with
 *       empty content. (A restore that empties a created file instead
 *       of deleting it leaves a zero-byte file where there was none.)</li>
 * </ul>
 *
 * <p><b>When the snapshot is taken.</b> The snapshot is taken at the
 * moment the checkpoint is CREATED, from the transcript's tool calls —
 * the file's content at that moment is the "before" state, because the
 * tools that would change it have not run yet.
 *
 * <p><b>Text only.</b> Binary files are NOT snapshotted (a byte array
 * per file is a memory cost with no restore benefit the user asked
 * for). A binary file that the session touched is recorded as
 * "not restorable" and the restore reports it, rather than silently
 * leaving it modified.
 *
 * <p><b>Never throws.</b> A missing file, a permission error, a
 * directory where a file was expected — all produce a recorded
 * outcome, never an exception that would abort the whole restore.
 */
public final class CodeCheckpointStore {

    /**
     * The tools that CHANGE a file, and the input field that names it.
     *
     * <p>Only the tools that WRITE are listed: a read does not change
     * the working tree, so there is nothing to restore.
     */
    private static final Map<String, String> WRITE_TOOLS = Map.of(
            "file_write", "file_path",
            "file_edit", "file_path");

    /** A file's saved state. */
    public record FileState(
            Path path,
            /** null = the file did not exist before the session touched it. */
            String content,
            /** true when the file did not exist before. */
            boolean created) {

        /** The label a restore report shows. */
        public String label() {
            return path.getFileName() == null ? path.toString() : path.getFileName().toString();
        }
    }

    /** One checkpoint: a set of files, at a moment. */
    public record Checkpoint(
            String id,
            /** the transcript index this checkpoint sits at. */
            int transcriptIndex,
            /** the user-message ordinal (0-based) this checkpoint sits at. */
            int userMessageIndex,
            /** a one-line summary for the menu. */
            String summary,
            List<FileState> files) {

        public boolean isEmpty() {
            return files.isEmpty();
        }
    }

    /** The outcome of a restore. */
    public record RestoreResult(
            List<String> restored,
            List<String> deleted,
            List<String> skipped,
            List<String> failed) {

        public boolean isComplete() {
            return failed.isEmpty();
        }

        public int changed() {
            return restored.size() + deleted.size();
        }
    }

    /**
     * What restoring one file would change.
     *
     * <p>★ THE UNKNOWN CASE IS A FACT, NOT A ZERO ★ A binary file, a
     * file that cannot be read, a path that is now a directory — the
     * restore SKIPS those, so the honest answer is "I do not know",
     * and saying "0 lines" would be a lie the user could act on.
     *
     * @param name the file's label
     * @param linesNow the file's current line count (-1 = unknown)
     * @param linesDelta how many lines differ from the snapshot
     * @param created the session CREATED the file
     * @param note why the preview is unknown, when it is
     */
    public record FilePreview(
            String name,
            int linesNow,
            int linesDelta,
            boolean created,
            String note) {

        /** A preview we could compute. */
        public static FilePreview of(String name, int linesNow, int linesDelta, boolean created) {
            return new FilePreview(name, linesNow, linesDelta, created, null);
        }

        /** A preview we could NOT compute — and why. */
        public static FilePreview unknown(String name, String note) {
            return new FilePreview(name, -1, -1, false, note);
        }

        /** Is this preview known? */
        public boolean isKnown() {
            return note == null;
        }

        /** Would restoring this file change it? */
        public boolean wouldChange() {
            return isKnown() && linesDelta > 0;
        }
    }

    /**
     * The checkpoints, oldest first.
     *
     * <p>★ A LINKED HASH MAP IS AN ACCESS-ORDERED QUEUE ★
     * {@link LinkedHashMap} with {@code accessOrder=false} keeps
     * INSERTION order, and the oldest entry is the FIRST one — so the
     * eviction below can drop the oldest without a second structure.
     */
    private final Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();

    /**
     * How many checkpoints are kept.
     *
     * <p>★ WHY THE STORE MUST BE BOUNDED ★
     *
     * <p>A checkpoint holds a FULL COPY of the content of every file
     * the session has touched so far. So the memory a session's
     * checkpoints take is
     *
     * <pre>
     *   turns x files-touched x file-size
     * </pre>
     *
     * <p>— quadratic in the session's length, and every turn makes it
     * worse. A long session that edits a handful of files would grow
     * the heap without bound, and the daemon would eventually die of
     * {@code OutOfMemoryError} rather than of anything the user could
     * see or act on.
     *
     * <p>★ WHY 64 ★ The rewind menu's window shows at most a dozen
     * entries at a time, so a user can only SEE about a dozen
     * checkpoints anyway. Keeping 64 gives them far more history than
     * any menu can show, while bounding the memory to a fixed
     * multiple of the working set. The eviction is FIFO — the OLDEST
     * checkpoint goes first — because the newest ones are the ones a
     * user rewinds to ("undo the last few things"), and because the
     * oldest is the one whose files are least likely to have changed
     * since.
     */
    public static final int MAX_CHECKPOINTS = 64;

    /**
     * Drop the oldest checkpoints until the store is within its bound.
     *
     * <p>★ SILENTLY, BUT COUNTED ★ The eviction is not logged (the
     * store has no logger) but it IS visible through {@link #size()}
     * and {@link #evictedCount()}, so the daemon can report it rather
     * than have the user discover it as a menu that mysteriously
     * starts at "point 40".
     */
    private void evictIfNeeded() {
        while (checkpoints.size() > MAX_CHECKPOINTS) {
            String oldest = checkpoints.keySet().iterator().next();
            checkpoints.remove(oldest);
            evicted++;
        }
    }

    /** How many checkpoints have been dropped because the store was full. */
    private long evicted;

    /** How many checkpoints the store currently holds. */
    public int size() {
        return checkpoints.size();
    }

    /**
     * How many checkpoints have been EVICTED over this store's life.
     *
     * <p>★ WHY THE USER SHOULD BE TOLD ★ A menu that silently starts
     * at "point 40" with no explanation looks like the daemon lost the
     * history. The number is what lets the caller say "the 12 oldest
     * points are no longer available" instead of pretending.
     */
    public long evictedCount() {
        return evicted;
    }

    /**
     * The tools that changed files, discovered from the transcript.
     *
     * <p>The scan is over the WHOLE transcript, because a checkpoint
     * at message N must know about every file the session has written
     * so far — otherwise restoring an early checkpoint would leave the
     * later writes in place.
     *
     * @param transcript the session's messages
     * @param cwd the session's working directory (relative paths resolve against it)
     * @return the touched files, in first-touch order
     */
    public static Set<Path> touchedFiles(List<Message> transcript, Path cwd) {
        Set<Path> out = new LinkedHashSet<>();
        if (transcript == null) return out;
        for (Message m : transcript) {
            for (ContentBlock b : m.content()) {
                if (!(b instanceof ContentBlock.ToolUseBlock use)) continue;
                String field = WRITE_TOOLS.get(use.name());
                if (field == null) continue;
                Object raw = use.input() == null ? null : use.input().get(field);
                if (!(raw instanceof String s) || s.isBlank()) continue;
                Path p = Path.of(s);
                if (!p.isAbsolute() && cwd != null) p = cwd.resolve(p);
                out.add(p.normalize());
            }
        }
        return out;
    }

    /**
     * Take a checkpoint of the files the session has touched SO FAR.
     *
     * <p>The snapshot captures the CURRENT content, which — because the
     * checkpoint is taken at a message boundary BEFORE the next turn's
     * writes — is the "before" state for anything that comes later.
     *
     * @param id the checkpoint's id
     * @param transcript the session's messages
     * @param transcriptIndex the transcript index the checkpoint sits at
     * @param userMessageIndex the user-message ordinal it sits at
     * @param cwd the session's working directory
     * @param summary a one-line summary for the menu
     * @return the checkpoint
     */
    public Checkpoint save(
            String id,
            List<Message> transcript,
            int transcriptIndex,
            int userMessageIndex,
            Path cwd,
            String summary) {
        List<FileState> files = new ArrayList<>();
        for (Path p : touchedFiles(transcript, cwd)) {
            try {
                if (!Files.exists(p)) {
                    // The file does not exist YET — the tool call that
                    // names it is in the transcript but the write may
                    // not have happened (a denied call, a failed edit).
                    // Record it as "created" so a restore DELETES it.
                    files.add(new FileState(p, null, true));
                } else if (Files.isRegularFile(p)) {
                    byte[] bytes = Files.readAllBytes(p);
                    if (isBinary(bytes)) {
                        // Not restorable — and saying so is better than
                        // writing mangled bytes back.
                        files.add(new FileState(p, null, false));
                    } else {
                        files.add(new FileState(p, new String(bytes, StandardCharsets.UTF_8), false));
                    }
                } else {
                    // A directory — not a file we can restore.
                    files.add(new FileState(p, null, false));
                }
            } catch (IOException e) {
                // Unreadable — record it as unrestorable rather than
                // failing the whole checkpoint.
                files.add(new FileState(p, null, false));
            }
        }
        Checkpoint cp = new Checkpoint(id, transcriptIndex, userMessageIndex, summary, files);
        checkpoints.put(id, cp);
        // ★ BOUND THE STORE ★ Every checkpoint holds a full copy of
        // every file the session has touched, so an unbounded store is
        // a quadratic memory leak (see MAX_CHECKPOINTS).
        evictIfNeeded();
        return cp;
    }

    /** The checkpoint with this id. */
    public Optional<Checkpoint> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(checkpoints.get(id));
    }

    /** Every checkpoint, in creation order. */
    public List<Checkpoint> list() {
        return new ArrayList<>(checkpoints.values());
    }

    /**
     * Forget every checkpoint.
     *
     * <p>★ THE EVICTION COUNT SURVIVES THE CLEAR ★ The counter says
     * "how many points this session has lost", and a clear does not
     * un-lose them: the memory was freed, and the user still cannot
     * go back to those points.
     */
    public void clear() {
        checkpoints.clear();
    }

    /**
     * Put the files back.
     *
     * <p>A file that did NOT exist before is DELETED — restoring it to
     * an empty file would leave a zero-byte file where the user's tree
     * had none.
     *
     * <p>A file whose content was not captured (binary, unreadable) is
     * SKIPPED and reported — the restore says what it did not do,
     * rather than claiming a clean restore.
     */
    public RestoreResult restore(Checkpoint cp) {
        List<String> restored = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (FileState fs : cp.files()) {
            try {
                if (fs.created()) {
                    if (Files.exists(fs.path())) {
                        Files.delete(fs.path());
                        deleted.add(fs.label());
                    }
                    continue;
                }
                if (fs.content() == null) {
                    skipped.add(fs.label());
                    continue;
                }
                Files.createDirectories(fs.path().getParent());
                Files.writeString(fs.path(), fs.content(), StandardCharsets.UTF_8);
                restored.add(fs.label());
            } catch (IOException | RuntimeException e) {
                failed.add(fs.label() + ": " + e.getMessage());
            }
        }
        return new RestoreResult(restored, deleted, skipped, failed);
    }

    /**
     * What a restore of this checkpoint would change, compared with the
     * files as they are NOW.
     *
     * <p>★ WHY THE PREVIEW LIVES HERE ★ The store holds BOTH sides of
     * the comparison: the snapshot it took at that point, and the live
     * file on disk. So it is the only place that can answer "what would
     * restoring this point undo?" without a second copy of the file
     * state somewhere else.
     *
     * <p>★ AND THE COUNTS ARE LINE COUNTS, NOT A DIFF ★ A full diff
     * needs a line-matching algorithm. The daemon already has one
     * ({@code org.aethercode.code.DiffUtils}), but it lives in a
     * different module and this store deliberately depends on nothing —
     * so the preview reports the honest, computable fact: how many LINES
     * differ between the snapshot and the file now. That is a bound on
     * the change, not a diff, and the label says so.
     *
     * <p>★ AND IT NEVER THROWS ★ A file that vanished, turned into a
     * directory, or cannot be read is reported as "unknown", not as an
     * exception that would take the whole menu down. The preview is
     * decoration on a menu; it must never be the reason the menu fails.
     *
     * @param cp the checkpoint
     * @return the per-file preview, in the checkpoint's own order
     */
    public List<FilePreview> preview(Checkpoint cp) {
        List<FilePreview> out = new ArrayList<>();
        if (cp == null) return out;
        for (FileState fs : cp.files()) {
            out.add(previewOne(fs));
        }
        return out;
    }

    /**
     * One file's preview: how many lines differ between the snapshot and
     * the file as it is now.
     *
     * @param fs the file's saved state
     * @return the preview
     */
    private FilePreview previewOne(FileState fs) {
        if (fs.created()) {
            // The session CREATED it, so it did not exist before. If it
            // exists now, restoring DELETES it — which is a change, and
            // the file's whole length is what would go.
            try {
                if (!Files.exists(fs.path())) {
                    return FilePreview.unknown(fs.label(), "already gone");
                }
                return FilePreview.of(fs.label(), linesOf(fs.path()), 0, true);
            } catch (IOException | RuntimeException e) {
                return FilePreview.unknown(fs.label(), "unreadable");
            }
        }
        if (fs.content() == null) {
            // Binary or unreadable — the restore SKIPS it, so it changes
            // nothing and there is nothing to preview.
            return FilePreview.unknown(fs.label(), "not restorable");
        }
        try {
            if (!Files.exists(fs.path())) {
                // The file is gone now. Restoring would bring it BACK,
                // so the change is the whole snapshot.
                return FilePreview.of(fs.label(), countLines(fs.content()), 0, false);
            }
            int now = linesOf(fs.path());
            int then = countLines(fs.content());
            return FilePreview.of(fs.label(), now, Math.abs(now - then), false);
        } catch (IOException | RuntimeException e) {
            return FilePreview.unknown(fs.label(), "unreadable");
        }
    }

    /** How many lines a file on disk has. */
    private static int linesOf(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        if (isBinary(bytes)) return -1;
        return countLines(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * How many lines a string spans.
     *
     * <p>★ THE SAME RULE AS THE TUI'S ★ An empty string is ZERO lines,
     * and a trailing newline does not start a new one — so "a\n" is ONE
     * line, not two. The rule lives in two languages here, and the gate
     * pins them to each other, because a preview that counts lines
     * differently from the card that reported the change would show the
     * user two different numbers for the same file.
     */
    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        String normalised = s.replace("\r\n", "\n");
        return normalised.split("\n", -1).length - (normalised.endsWith("\n") ? 1 : 0);
    }

    /**
     * Is this file's content binary?
     *
     * <p>A NUL byte in the first 8 KB is the conventional test — text
     * files do not contain them.
     */
    static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    /**
     * The tools that change a file, and the field that names it.
     *
     * <p>Exposed so the daemon and the gate can agree on the list
     * instead of each keeping their own (Lesson 1207).
     */
    public static Map<String, String> writeTools() {
        return WRITE_TOOLS;
    }

    /**
     * Is this message a USER message?
     *
     * <p>The checkpoints are anchored on user messages, because that is
     * where the user said something — a checkpoint between two agent
     * turns is not a place the user would ask to return to.
     */
    public static boolean isUserMessage(Message m) {
        return m != null && m.role() == Role.USER;
    }

    /**
     * Is this message a REAL user turn, or a side-note that merely
     * happens to carry {@code Role.USER}?
     *
     * <p>★ This distinction decides where a rewind lands. ★
     *
     * <p>The engine writes several {@code Role.USER} messages that the
     * user never typed:
     *
     * <ul>
     *   <li>{@code kind=session-anchor} — the periodic session anchor
     *       (R719);</li>
     *   <li>{@code kind=compaction-side-note} — the "[compaction] …"
     *       notice;</li>
     *   <li>{@code kind=transcript-trim-side-note} — the R716 trim
     *       notice;</li>
     *   <li>the {@code todo-ask-llm} prompt — the controller's own
     *       question to the model;</li>
     *   <li>the {@code [Resuming after user decision]} note.</li>
     * </ul>
     *
     * <p>★ Counting those as turns is how a "rewind to turn 3" lands on
     * a compaction notice instead of the user's third message. ★ The
     * two must not disagree — so there is ONE rule, here, and every
     * caller uses it.
     *
     * <p>A side-note is recognised by its {@code kind} metadata. A
     * message with NO metadata is a real user turn (the common case:
     * {@code Message.userText} puts {@code Map.of()} there).
     */
    public static boolean isRealUserTurn(Message m) {
        if (!isUserMessage(m)) return false;
        java.util.Map<String, Object> meta = m.metadata();
        if (meta == null || meta.isEmpty()) return true;
        Object kind = meta.get("kind");
        if (kind == null) return true;
        String k = String.valueOf(kind);
        // Every kind the engine writes for a message the USER did not
        // type. A new side-note kind must be added here or it will
        // silently shift every rewind target after it.
        return switch (k) {
            case "session-anchor",
                 "compaction-side-note",
                 "transcript-trim-side-note",
                 "todo-ask-llm",
                 "user-decision-resume",
                 "dedupe-stub" -> false;
            default -> true;
        };
    }

    /**
     * The transcript indices of the REAL user turns, in order.
     *
     * <p>★ ONE list, shared by the checkpoint producer and every
     * consumer that has to land on the same message. ★
     *
     * <p>The checkpoint list numbers its rows by the position in THIS
     * list; the restore and summarise paths resolve the same number
     * back through THIS list. Two independent counts would put a
     * "restore conversation" one message off — a corruption that looks
     * like the feature working.
     *
     * @param transcript the session's messages
     * @return the transcript index of each real user turn, ascending
     */
    public static List<Integer> userTurnIndices(List<Message> transcript) {
        List<Integer> out = new ArrayList<>();
        if (transcript == null) return out;
        for (int i = 0; i < transcript.size(); i++) {
            if (isRealUserTurn(transcript.get(i))) out.add(i);
        }
        return out;
    }

    /**
     * The transcript index of the Nth real user turn (0-based).
     *
     * @param transcript the session's messages
     * @param ordinal the 0-based turn ordinal
     * @return the transcript index, or -1 when there is no such turn
     */
    public static int userTurnIndex(List<Message> transcript, int ordinal) {
        List<Integer> idx = userTurnIndices(transcript);
        if (ordinal < 0 || ordinal >= idx.size()) return -1;
        return idx.get(ordinal);
    }
}
