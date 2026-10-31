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

import java.nio.file.Path;
import java.util.Objects;

/**
 * Raised when a workflow YAML cannot be parsed or fails a structural
 * check that the YAML loader owns (unreadable file, wrong root
 * type, missing required field, unknown tag, etc.). Validation
 * errors that a human can fix in the YAML file are surfaced here so
 * the CLI can show them with line numbers.
 *
 * <p>Semantic checks (kebab-case names, prompt content non-empty,
 * limits positive) live in {@link WorkflowValidator} and accumulate
 * into a {@code List<ValidationError>} instead of throwing.
 */
public class WorkflowParserException extends RuntimeException {

    private final Path source;

    public WorkflowParserException(String message) {
        this(message, null, null);
    }

    public WorkflowParserException(String message, Path source) {
        this(message, source, null);
    }

    public WorkflowParserException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public WorkflowParserException(String message, Path source, Throwable cause) {
        super(formatMessage(message, source), cause);
        this.source = source;
    }

    /** File the failure came from, or {@code null} if it was a
     *  string/byte-array load. */
    public Path source() { return source; }

    private static String formatMessage(String message, Path source) {
        Objects.requireNonNull(message, "message");
        if (source == null) return message;
        return source + ": " + message;
    }
}
