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

package org.aethercode.code.hooks;

/**
 * Client approval policy.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.approval_mode.ApprovalMode} {@code StrEnum}.
 * The values are passed to the hook transport under
 * <code>permission_mode</code> when serializing invocations.</p>
 */
public enum ApprovalMode {
    MANUAL,
    AUTO,
    YOLO;

    /** Parse a value case-insensitively, defaulting to {@link #MANUAL}. */
    public static ApprovalMode coerce(Object value) {
        if (value == null) {
            return MANUAL;
        }
        if (value instanceof ApprovalMode am) {
            return am;
        }
        String text = value.toString();
        if (text.isEmpty()) {
            return MANUAL;
        }
        try {
            return ApprovalMode.valueOf(text.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return MANUAL;
        }
    }
}
