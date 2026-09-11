package org.aethercode.core.fs.backend;

import java.util.Optional;

/**
 * Result of a single file upload operation. Designed to allow partial
 * success in batch operations: the response order matches the input
 * order, callers check the {@code error} field per entry.
 *
 * <p>Mirror of the deepagents <code>FileUploadResponse</code> dataclass.</p>
 */
public record FileUploadResponse(
        String path,
        Optional<String> error
) {
    public static FileUploadResponse success(String path) {
        return new FileUploadResponse(path, Optional.empty());
    }

    public static FileUploadResponse failure(String path, String error) {
        return new FileUploadResponse(path, Optional.ofNullable(error));
    }

    public boolean isSuccess() { return error.isEmpty(); }
}
