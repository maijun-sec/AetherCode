/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.patch;

/**
 * thrown when {@link PatchParser} encounters an unrecoverable error.
 * Carries the 1-based line number in the input where the failure occurred
 * (0 if unknown).
 */
public class PatchParseException extends RuntimeException {
    private final int lineNumber;

    public PatchParseException(String message, int lineNumber) {
        super(message + (lineNumber > 0 ? " (line " + lineNumber + ")" : ""));
        this.lineNumber = lineNumber;
    }

    public int lineNumber() { return lineNumber; }
}
