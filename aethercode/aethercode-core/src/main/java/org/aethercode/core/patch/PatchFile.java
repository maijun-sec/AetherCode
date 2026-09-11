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
