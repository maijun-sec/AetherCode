package org.aethercode.code;

import java.util.ArrayList;
import java.util.List;

/**
 * Diff utilities.
 *
 * <p>Java-native port of the Python {@code deepagents_code.diff_utils} module.
 * The Java port focuses on the small set of line-level diffs the TUI
 * uses to display file changes; for a full Myers diff the project depends
 * on {@code java-diff-utils} as a runtime dependency, but the basic
 * operations here are dependency-free.</p>
 */
public final class DiffUtils {
    private DiffUtils() {}

    /** A single line-level diff hunk. */
    public record Hunk(String kind, int oldLine, int newLine, String content) {
        public static Hunk context(String line, int oldLine, int newLine) {
            return new Hunk(" ", oldLine, newLine, line);
        }
        public static Hunk added(String line, int newLine) {
            return new Hunk("+", -1, newLine, line);
        }
        public static Hunk removed(String line, int oldLine) {
            return new Hunk("-", oldLine, -1, line);
        }
    }

    /**
     * Compute a line-level diff between two strings.
     *
     * <p>This is a simple LCS-based diff, not Myers; it's adequate for the
     * "show me what changed in a single file" use case the TUI has. For
     * larger files, callers should drop in {@code java-diff-utils}.</p>
     */
    public static List<Hunk> diffLines(String before, String after) {
        String[] oldLines = (before == null ? "" : before).split("\n", -1);
        String[] newLines = (after == null ? "" : after).split("\n", -1);
        int[][] lcs = computeLcs(oldLines, newLines);
        List<Hunk> hunks = new ArrayList<>();
        int i = oldLines.length, j = newLines.length;
        while (i > 0 && j > 0) {
            if (oldLines[i - 1].equals(newLines[j - 1])) {
                hunks.add(0, Hunk.context(oldLines[i - 1], i, j));
                i--; j--;
            } else if (lcs[i - 1][j] >= lcs[i][j - 1]) {
                hunks.add(0, Hunk.removed(oldLines[i - 1], i));
                i--;
            } else {
                hunks.add(0, Hunk.added(newLines[j - 1], j));
                j--;
            }
        }
        while (i > 0) {
            hunks.add(0, Hunk.removed(oldLines[i - 1], i));
            i--;
        }
        while (j > 0) {
            hunks.add(0, Hunk.added(newLines[j - 1], j));
            j--;
        }
        return hunks;
    }

    private static int[][] computeLcs(String[] a, String[] b) {
        int m = a.length, n = b.length;
        int[][] lcs = new int[m + 1][n + 1];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                if (a[i].equals(b[j])) {
                    lcs[i + 1][j + 1] = lcs[i][j] + 1;
                } else {
                    lcs[i + 1][j + 1] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }
        return lcs;
    }

    /** Render a list of hunks as a unified-diff-style text. */
    public static String renderUnified(List<Hunk> hunks) {
        StringBuilder sb = new StringBuilder();
        for (Hunk h : hunks) {
            sb.append(h.kind()).append(h.content()).append('\n');
        }
        return sb.toString();
    }
}
