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

import java.nio.file.Path;

import org.aethercode.core.tool.Tool;

import java.util.List;

/**
 * R407: somebody has to learn what a file looked like BEFORE the session
 * first wrote to it.
 *
 * <h2>★ WHY THE FACT HAS TO BE CAPTURED AT THE WRITE ★</h2>
 *
 * <p>A turn-boundary checkpoint is taken at the START of a turn, from the
 * tool calls the transcript has recorded SO FAR. So a file the session
 * touches for the FIRST time in that turn is not in that turn's point —
 * and a restore of that point does not undo it. Measured (R407):
 *
 * <pre>
 *   boundary point files = []
 *   restore -> restored=[] deleted=[] skipped=[] failed=[]
 *   the file the turn created: still on disk
 * </pre>
 *
 * <p>That is a rewind that reports success and changes nothing — and the
 * cancel point's own javadoc promises the opposite: "undoing the
 * cancelled turn's partial work is the PREVIOUS turn-boundary point's
 * job".
 *
 * <p>⇒ The only moment that knows the answer is the moment of the write:
 * before it, the file either existed (with some content) or it did not.
 * Afterwards the answer is gone — the write has overwritten it. So the
 * write path tells the recorder, and the recorder remembers.
 *
 * <h2>★ WHY THE TOOL CALLS IT, NOT THE EXECUTOR ★</h2>
 *
 * <p>The tool already resolves the path against the session's working
 * directory and already refuses the paths it may not touch. An executor
 * that re-derived the path would be a SECOND copy of that resolution
 * (R380: a second list drifts). So the tool calls the recorder with the
 * path it is about to write.
 */
public interface FileTouchRecorder {

    /**
     * The key the recorder travels under in
     * {@link Tool.CallContext#extras()}.
     */
    String EXTRAS_KEY = "aethercode.file_touch_recorder";

    /**
     * Called by a write tool BEFORE it writes.
     *
     * @param path the absolute path the tool is about to write
     */
    void beforeWrite(Path path);

    /**
     * Tell the recorder about a write, if the context carries one.
     *
     * <p>★ ONE CALL SITE FOR EVERY WRITE TOOL ★ the lookup, the
     * "no recorder" case and the null path all live here, so the three
     * write tools each say one line instead of three copies of the same
     * lookup.
     *
     * @param ctx the call context (may be null)
     * @param path the path about to be written (may be null)
     */
    static void noteBeforeWrite(Tool.CallContext ctx, Path path) {
        if (ctx == null || path == null) return;
        Object recorder = ctx.extra(EXTRAS_KEY);
        if (recorder instanceof FileTouchRecorder r) r.beforeWrite(path);
    }

    /**
     * R415: a recorder that forwards to SEVERAL recorders.
     *
     * <p>★ WHY ★ two consumers need the same moment: the checkpoint store
     * (which remembers the file for a rewind) and the turn-change tracker
     * (which remembers it for the consistency check). The write path
     * carries ONE recorder in its context, so the two have to be combined
     * here rather than by two lookups at every call site.
     *
     * <p>★ ONE FAILURE DOES NOT SWALLOW THE OTHERS ★ a recorder that
     * throws must not stop the others from being told — a broken
     * bookkeeping path is not a reason to lose the file's history.
     *
     * @param parts the recorders to notify, in order
     * @return a recorder that notifies all of them
     */
    static FileTouchRecorder composite(FileTouchRecorder... parts) {
        if (parts == null || parts.length == 0) {
            return path -> { };
        }
        List<FileTouchRecorder> list = java.util.List.of(parts);
        return path -> {
            for (FileTouchRecorder r : list) {
                if (r == null) continue;
                try {
                    r.beforeWrite(path);
                } catch (RuntimeException ignored) {
                    // one broken recorder must not silence the others
                }
            }
        };
    }
}
