package org.aethercode.core.fs.backend;

import java.util.Optional;

/**
 * Result of a backend edit operation.
 *
 * <p>Mirror of the deepagents <code>EditResult</code> dataclass.</p>
 */
public record EditResult(
        Optional<String> error,
        Optional<String> path,
        Optional<Integer> occurrences
) {
    public static EditResult success(String path, int occurrences) {
        return new EditResult(Optional.empty(), Optional.ofNullable(path), Optional.of(occurrences));
    }

    public static EditResult failure(String error) {
        return new EditResult(Optional.ofNullable(error), Optional.empty(), Optional.empty());
    }
}
