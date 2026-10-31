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

package org.aethercode.core.patch;

import java.util.Objects;

/**
 * a single line in a unified diff hunk. {@code kind} is one of
 * {@code ' '}, {@code '+'}, {@code '-'} for context, addition, removal.
 * Old/new line numbers may be 0 (e.g. for an addition that has no old side).
 */
public record DiffLine(char kind, String content, int oldLineNo, int newLineNo) {
    public DiffLine {
        if (kind != ' ' && kind != '+' && kind != '-') {
            throw new IllegalArgumentException("kind must be ' ', '+' or '-', got '" + kind + "'");
        }
        content = content == null ? "" : content;
    }
    public boolean isContext() { return kind == ' '; }
    public boolean isAddition() { return kind == '+'; }
    public boolean isRemoval() { return kind == '-'; }

    /** the change as a single string like " +hello". Used for re-serialization. */
    public String asRaw() {
        return kind + content;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof DiffLine dl)) return false;
        return kind == dl.kind
                && oldLineNo == dl.oldLineNo
                && newLineNo == dl.newLineNo
                && Objects.equals(content, dl.content);
    }
}
