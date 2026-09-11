package org.aethercode.core.fs.backend;

import java.util.List;
import java.util.Optional;

/**
 * Result of a backend ls operation.
 *
 * <p>Mirror of the deepagents <code>LsResult</code> dataclass.</p>
 */
public record LsResult(
        Optional<String> error,
        Optional<List<FileInfo>> entries
) {
    public static LsResult of(List<FileInfo> entries) {
        return new LsResult(Optional.empty(), Optional.ofNullable(entries));
    }

    public static LsResult error(String message) {
        return new LsResult(Optional.ofNullable(message), Optional.empty());
    }
}
