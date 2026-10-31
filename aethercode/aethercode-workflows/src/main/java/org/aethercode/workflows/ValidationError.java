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

package org.aethercode.workflows;

import java.util.Objects;

/**
 * One validation finding produced by {@link WorkflowValidator}.
 * The validator accumulates these into a {@code List<ValidationError>}
 * (rather than throwing on the first failure) so the user sees every
 * problem with the workflow in one pass.
 *
 * <p>{@link #path()} is a dotted JSON-path-ish pointer to the
 * offending field ({@code "prompts[2].content"}, {@code "limits.tokens"}),
 * which the CLI prints so the user can fix the file in their editor.
 */
public record ValidationError(String path, String message) {

    public ValidationError {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
    }

    /** Static factory for a top-level error. */
    public static ValidationError of(String message) {
        return new ValidationError("(root)", message);
    }

    @Override
    public String toString() {
        return path + ": " + message;
    }
}
