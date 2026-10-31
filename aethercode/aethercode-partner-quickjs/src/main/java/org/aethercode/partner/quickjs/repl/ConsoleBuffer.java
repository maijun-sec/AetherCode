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

package org.aethercode.partner.quickjs.repl;

import org.aethercode.partner.quickjs.format.Format;

/**
 * Accumulates <code>console.*</code> output between evals. 1:1 port
 * of the Python {@code _ConsoleBuffer} class in
 * <code>_repl.py</code>.
 *
 * <p>Shared by the three host functions installed on each context.
 * We do not bother distinguishing log/warn/error in the output
 * format &mdash; the model does not care about the level, and
 * flattening keeps the returned string smaller.</p>
 */
public final class ConsoleBuffer {

    private final int maxChars;
    private final StringBuilder stdout = new StringBuilder();
    private int droppedChars = 0;

    public ConsoleBuffer(int maxChars) {
        this.maxChars = Math.max(0, maxChars);
    }

    /**
     * Append a single console call. {@code level} is flattened (see
     * class docstring); the line is the space-joined string form of
     * the args.
     */
    public void append(String level, Object[] args) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) line.append(' ');
            line.append(Format.stringify(args[i]));
        }
        String chunk = stdout.length() == 0 ? line.toString() : "\n" + line;
        int remaining = maxChars - stdout.length();
        if (remaining <= 0) {
            droppedChars += chunk.length();
            return;
        }
        String kept = chunk.length() > remaining ? chunk.substring(0, remaining) : chunk;
        stdout.append(kept);
        droppedChars += chunk.length() - kept.length();
    }

    /**
     * Drain the buffer: return the accumulated stdout and the number
     * of chars dropped, then reset the buffer. Returns {@code ("", 0)}
     * when there is nothing to drain.
     */
    public DrainResult drain() {
        if (stdout.length() == 0 && droppedChars == 0) {
            return new DrainResult("", 0);
        }
        String out = stdout.toString();
        int dropped = droppedChars;
        stdout.setLength(0);
        droppedChars = 0;
        return new DrainResult(out, dropped);
    }

    /** Result of {@link #drain()}: collected stdout + dropped-char count. */
    public record DrainResult(String stdout, int droppedChars) {}
}
