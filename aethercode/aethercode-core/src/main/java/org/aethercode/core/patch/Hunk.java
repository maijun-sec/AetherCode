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
