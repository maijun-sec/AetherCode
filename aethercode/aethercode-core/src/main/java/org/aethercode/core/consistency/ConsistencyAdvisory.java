/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, distribute, sublicense, and/or sell
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

import org.aethercode.core.tool.Tool;
import org.aethercode.core.writescope.SessionPaths;

import java.nio.file.Path;
import java.util.List;

/**
 * R415: the IMMEDIATE advisory — the check that runs right after a write,
 * while the model is still in the middle of the turn.
 *
 * <h2>★ WHY THE ADVISORY IS THE IMPORTANT HALF ★</h2>
 * <p>The end-of-turn verdict tells the USER what went wrong. The advisory
 * tells the MODEL, at the moment it can still do something about it: the
 * tool result is the model's feedback channel, so the warning lands
 * exactly where the model is looking.
 *
 * <p>⇒ A turn that changed one of two copies becomes a turn that says
 * "I changed the other one too", instead of a broken tree the user has to
 * find.
 *
 * <h2>★ WHY IT IS CHEAP ENOUGH TO RUN ON EVERY WRITE ★</h2>
 * <p>Most writes change code, not constants. The advisory first asks
 * whether the write moved a <b>value-bearing declaration</b> at all — and
 * if it did not, it returns immediately, without walking the tree. The
 * expensive part (a walk plus a parse of every file) only happens when
 * there is something that could possibly be inconsistent.
 *
 * <h2>★ WHY IT IS NOT A HOOK ★</h2>
 * <p>A hook sees the tool's input and its result — not the content the
 * file had <b>before</b> the write. The before-content is the whole
 * question ("which declaration changed?"), and only the tool has it. So
 * the check lives where the before-content lives.
 */
public final class ConsistencyAdvisory {

    private ConsistencyAdvisory() {}

    /**
     * The warning to append to a write's result, or null when there is
     * nothing to say.
     *
     * @param ctx the call context (for the session's working directory)
     * @param path the file that was written
     * @param before its content BEFORE the write (null when it did not exist)
     * @param after its content AFTER the write
     * @return the warning, or null
     */
    public static String check(Tool.CallContext ctx, Path path, String before, String after) {
        if (path == null) return null;
        ConsistencyVerifier.FileChange change = new ConsistencyVerifier.FileChange(path, before, after);
        // ★ THE FAST PATH ★ no declaration moved ⇒ nothing to be
        // inconsistent about ⇒ the tree is not walked at all.
        if (!ConsistencyVerifier.changesADeclaration(change)) return null;
        Path root = SessionPaths.cwdOf(ctx);
        ConsistencyVerdict verdict = ConsistencyVerifier.verify(List.of(change), root);
        if (!verdict.hasFindings()) return null;
        return verdict.report();
    }

    /**
     * Append the warning to a tool result body.
     *
     * @param output the current body
     * @param advisory the warning (null ⇒ the body is unchanged)
     * @return the new body
     */
    public static String append(String output, String advisory) {
        if (advisory == null || advisory.isBlank()) return output;
        String base = output == null ? "" : output;
        return base + "\n\n" + advisory;
    }
}
