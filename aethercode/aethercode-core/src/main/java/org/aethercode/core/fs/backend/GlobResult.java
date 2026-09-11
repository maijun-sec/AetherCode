package org.aethercode.core.fs.backend;

import java.util.List;
import java.util.Optional;

/**
 * Result of a backend glob operation.
 *
 * <p>Mirror of the deepagents <code>GlobResult</code> dataclass.</p>
 */
public record GlobResult(
        Optional<String> error,
        Optional<List<FileInfo>> matches,
        boolean truncated
) {
    public static GlobResult of(List<FileInfo> matches) {
        return new GlobResult(Optional.empty(), Optional.ofNullable(matches), false);
    }

    public static GlobResult of(List<FileInfo> matches, boolean truncated) {
        return new GlobResult(Optional.empty(), Optional.ofNullable(matches), truncated);
    }

    public static GlobResult error(String message) {
        return new GlobResult(Optional.ofNullable(message), Optional.empty(), false);
    }
}
