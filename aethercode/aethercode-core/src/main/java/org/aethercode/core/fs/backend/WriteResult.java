/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.fs.backend;

import java.util.Optional;

/**
 * Result of a backend write operation.
 *
 * <p>Mirror of the deepagents <code>WriteResult</code> dataclass.</p>
 */
public record WriteResult(
        Optional<String> error,
        Optional<String> path
) {
    public static WriteResult success(String path) {
        return new WriteResult(Optional.empty(), Optional.ofNullable(path));
    }

    public static WriteResult failure(String error) {
        return new WriteResult(Optional.ofNullable(error), Optional.empty());
    }
}
