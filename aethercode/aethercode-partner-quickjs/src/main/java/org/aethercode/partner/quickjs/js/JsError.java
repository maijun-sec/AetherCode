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

package org.aethercode.partner.quickjs.js;

/**
 * Base class for every QuickJS exception surfaced to Java. 1:1 port of
 * <code>quickjs_rs.JSError</code> (and the surrounding exception
 * hierarchy). Concrete subclasses map to the well-known error types
 * the REPL distinguishes in {@code EvalOutcome.errorType}.
 */
public class JsError extends RuntimeException {

    private final String name;
    private final String message;
    private final String stack;

    public JsError(String name, String message, String stack) {
        super(message);
        this.name = name == null ? "Error" : name;
        this.message = message == null ? "" : message;
        this.stack = stack;
    }

    public JsError(String message) {
        this("Error", message, null);
    }

    /** The JS error type (e.g. {@code "TypeError"}). */
    public String name() {
        return name;
    }

    @Override
    public String getMessage() {
        return message;
    }

    /** The JS error stack, or {@code null} if not provided. */
    public String stack() {
        return stack;
    }
}
