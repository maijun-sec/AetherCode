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

import java.util.List;
import java.util.Objects;

/**
 * a single file's worth of unified diff hunks. {@code oldPath} and
 * {@code newPath} come from the {@code ---} / {@code +++} headers; either
 * may be {@code "/dev/null"} for add/remove-only files.
 */
public record PatchFile(String oldPath, String newPath, List<Hunk> hunks) {
    public PatchFile {
        oldPath = oldPath == null ? "" : oldPath;
        newPath = newPath == null ? "" : newPath;
        Objects.requireNonNull(hunks, "hunks");
        hunks = List.copyOf(hunks);
    }

    public int totalAdditions() {
        int n = 0;
        for (Hunk h : hunks) n += h.additions();
        return n;
    }

    public int totalRemovals() {
        int n = 0;
        for (Hunk h : hunks) n += h.removals();
        return n;
    }
}
