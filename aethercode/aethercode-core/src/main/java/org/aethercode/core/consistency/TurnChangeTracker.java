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

package org.aethercode.core.consistency;

import org.aethercode.core.transcript.FileTouchRecorder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R415: what THIS TURN changed — the before/after pair of every file the
 * turn wrote.
 *
 * <h2>★ WHY IT IS PER TURN ★</h2>
 * <p>The checkpoint store remembers what a file looked like before the
 * SESSION first wrote to it. That is the right memory for "undo this
 * turn", but it is the wrong one for "what did <b>this</b> turn change":
 * after ten turns, the session's first snapshot says nothing about what
 * turn ten did.
 *
 * <p>⇒ This tracker resets at every turn boundary (the same seam the
 * checkpoint producer uses) and remembers the content of each file
 * <b>before the turn's first write to it</b>. That is exactly the
 * "before" the consistency check needs.
 *
 * <h2>★ WHY IT IS A RECORDER ★</h2>
 * <p>The moment before a write is the only moment that knows what the file
 * held. After the write, the answer is gone — the write overwrote it. So
 * the write path tells this tracker, exactly as it tells the checkpoint
 * store.
 */
public final class TurnChangeTracker implements FileTouchRecorder {

    /** path -> the content before this turn's first write to it. */
    private final Map<Path, String> before = new LinkedHashMap<>();

    /**
     * Start a new turn.
     *
     * <p>★ IT FORGETS ★ the previous turn's changes are not this turn's
     * changes. Keeping them would make the check report a copy that was
     * already fixed two turns ago.
     */
    public synchronized void beginTurn() {
        before.clear();
    }

    @Override
    public synchronized void beforeWrite(Path path) {
        if (path == null) return;
        Path key = path.toAbsolutePath().normalize();
        if (before.containsKey(key)) return;
        before.put(key, readText(key));
    }

    /** How many files this turn has written. */
    public synchronized int size() {
        return before.size();
    }

    /** Has this turn written anything? */
    public synchronized boolean isEmpty() {
        return before.isEmpty();
    }

    /**
     * The files this turn actually changed.
     *
     * <p>★ A FILE THAT DID NOT CHANGE IS NOT A CHANGE ★ the recorder is
     * called before the write, so a write that then FAILED would still
     * have left an entry. Comparing the two contents removes it.
     */
    public synchronized List<ConsistencyVerifier.FileChange> changes() {
        List<ConsistencyVerifier.FileChange> out = new ArrayList<>();
        for (Map.Entry<Path, String> e : before.entrySet()) {
            String now = readText(e.getKey());
            String was = e.getValue();
            if (was == null && now == null) continue;      // created and gone
            if (was != null && was.equals(now)) continue;   // unchanged
            out.add(new ConsistencyVerifier.FileChange(e.getKey(), was, now));
        }
        return out;
    }

    /** The file's content, or null when it does not exist or is not text. */
    private static String readText(Path p) {
        try {
            if (!Files.isRegularFile(p)) return null;
            byte[] bytes = Files.readAllBytes(p);
            int probe = Math.min(bytes.length, 8192);
            for (int i = 0; i < probe; i++) {
                if (bytes[i] == 0) return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
