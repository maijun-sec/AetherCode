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

package org.aethercode.memory;

/**
 * R230 (G10): sensitivity label for memory entries.
 *
 * <p>Drives the {@link MemoryRecall} PII filter — entries with
 * {@link #PII} or {@link #SENSITIVE} are hidden from the default
 * recall unless the caller explicitly opts in.
 *
 * <p>Default for new entries (whether user-typed or LLM-extracted)
 * is {@link #INTERNAL}. The caller (or the LLM extraction prompt)
 * is expected to upgrade the label when the content looks sensitive
 * (auth tokens, emails, file paths under {@code ~/.ssh}, etc.).
 */
public enum Sensitivity {
    /** Safe to share, log, send in any RPC. Examples: project name, build command. */
    PUBLIC,
    /** Default. Safe to recall and persist; not PII but not for public export. */
    INTERNAL,
    /** Should not be recalled into prompts that leave the local machine. */
    SENSITIVE,
    /** Personally identifying information. Hidden by default in recall. */
    PII;

    /** True iff this entry should be hidden from a default {@code recall()} call. */
    public boolean isHiddenByDefault() {
        return this == PII || this == SENSITIVE;
    }

    /** Parse a free-form string (e.g. from RPC input) into an enum, defaulting to INTERNAL. */
    public static Sensitivity parseOrDefault(String s) {
        if (s == null || s.isBlank()) return INTERNAL;
        try { return Sensitivity.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return INTERNAL; }
    }
}
