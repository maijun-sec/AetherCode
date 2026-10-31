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

/**
 * Normalized result of a single REPL eval. 1:1 port of the Python
 * <code>EvalOutcome</code> dataclass in <code>_repl.py</code>.
 *
 * <p>Exactly one of {@link #result()} / {@link #errorType()} is
 * meaningful per call; {@link #stdout()} is collected from
 * <code>console.*</code> regardless.</p>
 */
public record EvalOutcome(
        String stdout,
        int stdoutTruncatedChars,
        String result,
        String resultKind,
        String errorType,
        String errorMessage,
        String errorStack
) {

    /** Outcome with every field at its default (empty success). */
    public static EvalOutcome empty() {
        return new EvalOutcome("", 0, null, null, null, null, null);
    }

    /**
     * Return a copy of this outcome with the given {@code stdout} and
     * {@code stdoutTruncatedChars}. The remaining fields are preserved
     * verbatim &mdash; this is the "console drain" step that runs in
     * the <code>finally</code> of the eval.
     */
    public EvalOutcome withStdout(String stdout, int truncated) {
        return new EvalOutcome(stdout, truncated, result, resultKind, errorType, errorMessage, errorStack);
    }

    /**
     * Return a copy of this outcome with the given success result and
     * (optional) kind. Clears any prior error fields.
     */
    public EvalOutcome withResult(String result, String kind) {
        return new EvalOutcome(stdout, stdoutTruncatedChars, result, kind, null, null, null);
    }

    /**
     * Return a copy of this outcome with the given error fields.
     * Clears any prior success fields.
     */
    public EvalOutcome withError(String type, String message, String stack) {
        return new EvalOutcome(stdout, stdoutTruncatedChars, null, null, type, message, stack);
    }
}
