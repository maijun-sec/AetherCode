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
 * a single unified diff hunk. {@code oldStart}/{@code oldCount} and
 * {@code newStart}/{@code newCount} come from the {@code @@} header. When
 * the count is omitted in the header (e.g. {@code @@ -1 +1 @@}), the
 * corresponding count defaults to 1.
 */
public record Hunk(
        int oldStart,
        int oldCount,
        int newStart,
        int newCount,
        List<DiffLine> lines,
        String sectionHeading
) {
    public Hunk {
        if (oldStart < 0) throw new IllegalArgumentException("oldStart must be >= 0");
        if (newStart < 0) throw new IllegalArgumentException("newStart must be >= 0");
        if (oldCount < 0) throw new IllegalArgumentException("oldCount must be >= 0");
        if (newCount < 0) throw new IllegalArgumentException("newCount must be >= 0");
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
        if (sectionHeading == null) sectionHeading = "";
    }

    public int additions() {
        int n = 0;
        for (DiffLine l : lines) if (l.isAddition()) n++;
        return n;
    }

    public int removals() {
        int n = 0;
        for (DiffLine l : lines) if (l.isRemoval()) n++;
        return n;
    }

    /** re-render the hunk in unified-diff format (without the file header). */
    public String toUnifiedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("@@ -").append(oldStart).append(',').append(oldCount)
          .append(" +").append(newStart).append(',').append(newCount).append(" @@");
        if (!sectionHeading.isEmpty()) sb.append(' ').append(sectionHeading);
        sb.append('\n');
        for (DiffLine l : lines) sb.append(l.asRaw()).append('\n');
        return sb.toString();
    }
}
