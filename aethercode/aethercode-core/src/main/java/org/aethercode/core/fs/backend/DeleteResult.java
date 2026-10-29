/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.fs.backend;

import java.util.Optional;

/**
 * Result of a backend delete operation.
 *
 * <p>Mirror of the deepagents <code>DeleteResult</code> dataclass.</p>
 */
public record DeleteResult(
        Optional<String> error,
        Optional<String> path
) {
    public static DeleteResult success(String path) {
        return new DeleteResult(Optional.empty(), Optional.ofNullable(path));
    }

    public static DeleteResult failure(String error) {
        return new DeleteResult(Optional.ofNullable(error), Optional.empty());
    }
}
