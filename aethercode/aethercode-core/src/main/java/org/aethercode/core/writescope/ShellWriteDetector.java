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

package org.aethercode.core.writescope;

import org.aethercode.core.consistency.ConsistencyVerdict;
import org.aethercode.core.consistency.ConsistencyVerifier;
import org.aethercode.core.consistency.ProjectIndex;
import org.aethercode.core.tool.Tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R415: the SHELL WRITE DETECTOR — the third channel, which the write
 * scope could not cover.
 *
 * <h2>★ WHY THE SHELL NEEDS ITS OWN DETECTOR ★</h2>
 * <p>The write scope covers the tools that NAME a path:
 * {@code file_write}, {@code file_edit}, {@code notebook_edit}. The shell
 * names nothing — {@code sed -i}, {@code cat &gt;}, {@code python -c} and
 * {@code tee} all write, and no path check can constrain them. R415's
 * survey found the consequence: <b>the shell write channel was completely
 * invisible</b>. The checkpoint store never saw those files, the
 * transcript never mentioned them, and the consistency check never
 * looked at them — so a model that "edited" a config with {@code sed}
 * left a broken tree and reported success.
 *
 * <h2>★ THE DETECTOR'S ANSWER COMES FROM THE TREE, NOT FROM THE COMMAND ★</h2>
 * <p>Parsing shell commands to guess what they write is a losing game
 * ({@code $(...)}, {@code xargs}, {@code make}, a python one-liner…). So
 * the detector does not read the command. It takes an INDEX of the tree
 * before the command runs and another one after, and <b>diffs</b> them.
 * That is the same mechanism the write tools use (the index is what the
 * consistency check reads), so the shell produces the same kind of
 * evidence — and the same judge can serve both.
 *
 * <h2>★ THE COST IS PAID ONCE, NOT TWICE ★</h2>
 * <p>Two scans per command would be wasteful, so the baseline is
 * <b>cached</b> and re-used by the next command. It is dropped whenever a
 * write tool touches the tree (the tracker tells it), because then the
 * cached baseline is stale and would mis-attribute the tool's change to
 * the shell.
 *
 * <h2>★ WHAT IT DOES NOT COVER ★</h2>
 * <ul>
 *   <li><b>Writes outside the tree.</b> The detector watches the session's
 *       working directory; a command that writes to {@code /etc} leaves no
 *       trace in it. The shell's own scope check (the {@code cd ..}
 *       heuristic) is the only guard there, and it is a heuristic.</li>
 *   <li><b>Ignored files.</b> Build output and anything the project's own
 *       {@code .gitignore} excludes is not source, so it is not reported.
 *       That is on purpose: reporting every {@code target/} file would
 *       drown the signal.</li>
 *   <li><b>Background commands.</b> A backgrounded command outlives the
 *       call, so the "after" index would be taken while it still runs.</li>
 * </ul>
 */
public final class ShellWriteDetector {

    /**
     * The key the detector travels under in
     * {@link Tool.CallContext#extras()}.
     */
    public static final String EXTRAS_KEY = "aethercode.shell_write_detector";

    /**
     * What one command did to the tree.
     *
     * @param changed the files whose declarations moved
     * @param verdict what the tree looks like now
     */
    public record Observation(List<ConsistencyVerifier.FileSymbols> changed,
                              ConsistencyVerdict verdict) {
        public Observation {
            changed = changed == null ? List.of() : List.copyOf(changed);
        }

        /** Did the command change anything the check cares about? */
        public boolean hasChanges() {
            return !changed.isEmpty();
        }
    }

    /** The root the cached baseline belongs to. */
    private Path baselineRoot;

    /** The cached baseline (null after an invalidation). */
    private ProjectIndex baselineIndex;

    /**
     * The shell's changes in THIS turn, per file.
     *
     * <p>★ WHY THE TURN SCOPES IT ★ the check runs at the end of a turn
     * and compares "before" with "now". A change from three turns ago is
     * not this turn's change, and reporting it again would be the
     * "乱七八糟" the user complained about.
     */
    private final Map<Path, ConsistencyVerifier.FileSymbols> pending = new LinkedHashMap<>();

    /**
     * The detector in the call context, or null.
     *
     * @param ctx the call context (may be null)
     * @return the detector, or null when the shell channel is not observed
     */
    public static ShellWriteDetector fromContext(Tool.CallContext ctx) {
        if (ctx == null) return null;
        Object installed = ctx.extra(EXTRAS_KEY);
        return installed instanceof ShellWriteDetector d ? d : null;
    }

    /**
     * Start a new turn: forget the shell's previous changes.
     */
    public synchronized void beginTurn() {
        pending.clear();
    }

    /**
     * The cached baseline is stale — drop it.
     *
     * <p>★ WHO CALLS THIS ★ the write path. A tool write changes the
     * tree, so the cached baseline no longer describes the tree, and the
     * next diff would blame the shell for the tool's change.
     */
    public synchronized void invalidate() {
        baselineIndex = null;
        baselineRoot = null;
    }

    /**
     * The index to diff against after the command runs.
     *
     * @param root the session's working directory
     * @return the baseline, or null when there is nothing to watch
     */
    public synchronized ProjectIndex begin(Path root) {
        if (root == null) return null;
        if (baselineIndex != null && root.equals(baselineRoot)) return baselineIndex;
        baselineRoot = root;
        baselineIndex = ProjectIndex.scan(root);
        return baselineIndex;
    }

    /**
     * What the command did to the tree.
     *
     * @param root the session's working directory
     * @param before the baseline {@link #begin(Path)} returned
     * @return the observation (never null)
     */
    public synchronized Observation end(Path root, ProjectIndex before) {
        if (root == null) return new Observation(List.of(), ConsistencyVerdict.nothingToCheck(0));
        ProjectIndex now = ProjectIndex.scan(root);
        baselineRoot = root;
        baselineIndex = now;
        if (before == null) return new Observation(List.of(), ConsistencyVerdict.nothingToCheck(now.fileCount()));
        List<ConsistencyVerifier.FileSymbols> moved = new ArrayList<>();
        for (ConsistencyVerifier.FileSymbols fs : ConsistencyVerifier.symbolsBetween(before, now)) {
            if (fs.changedSymbols().isEmpty()) continue;
            moved.add(fs);
            record(fs);
        }
        ConsistencyVerdict verdict = moved.isEmpty()
                ? ConsistencyVerdict.nothingToCheck(now.fileCount())
                : ConsistencyVerifier.verifySymbols(moved, now);
        return new Observation(moved, verdict);
    }

    /**
     * Remember a change, merging it with what this turn already saw.
     *
     * <p>★ THE OLDEST "BEFORE", THE NEWEST "AFTER" ★ two commands can
     * change the same file; the turn's "before" is what the file held
     * before the FIRST of them, and the turn's "after" is what it holds
     * now.
     */
    private void record(ConsistencyVerifier.FileSymbols fs) {
        Path key = fs.file().toAbsolutePath().normalize();
        ConsistencyVerifier.FileSymbols seen = pending.get(key);
        if (seen == null) {
            pending.put(key, fs);
            return;
        }
        pending.put(key, new ConsistencyVerifier.FileSymbols(fs.file(), seen.before(), fs.after()));
    }

    /**
     * The shell's changes in this turn.
     *
     * @return one entry per file the shell changed
     */
    public synchronized List<ConsistencyVerifier.FileSymbols> pendingChanges() {
        return new ArrayList<>(pending.values());
    }

    /**
     * The report for a shell command that changed the tree.
     *
     * <p>★ THE MODEL NEEDS TO KNOW ★ the shell's output says what the
     * command printed, not what it changed. The model has to be told, or
     * it will report "done" while the tree says otherwise.
     *
     * @param observation what the command did
     * @param root the session's working directory
     * @return the report, or null when the command changed nothing
     */
    public static String report(Observation observation, Path root) {
        if (observation == null || !observation.hasChanges()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("[SHELL WRITE DETECTED] this command changed ")
                .append(observation.changed().size())
                .append(observation.changed().size() == 1 ? " file" : " files")
                .append(" in the working tree:");
        for (ConsistencyVerifier.FileSymbols fs : observation.changed()) {
            List<String> moved = new ArrayList<>(fs.changedSymbols());
            sb.append("\n  - ").append(relative(fs.file(), root));
            if (!moved.isEmpty()) sb.append(" (").append(String.join(", ", moved)).append(")");
        }
        ConsistencyVerdict verdict = observation.verdict();
        if (verdict != null && verdict.hasFindings()) {
            sb.append("\n").append(verdict.report());
        }
        return sb.toString();
    }

    /** The path relative to the root, when it is inside it. */
    private static String relative(Path file, Path root) {
        if (file == null) return "?";
        if (root == null) return file.toString();
        try {
            Path base = root.toAbsolutePath().normalize();
            Path f = file.toAbsolutePath().normalize();
            if (f.startsWith(base)) return base.relativize(f).toString();
        } catch (RuntimeException ignored) {
            // an unusable path is reported as it is
        }
        return file.toString();
    }
}
