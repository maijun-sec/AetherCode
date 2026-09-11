package org.aethercode.core.fs.backend;

import java.util.Optional;

/**
 * Result of a single file download operation.
 *
 * <p>Mirror of the deepagents <code>FileDownloadResponse</code> dataclass.</p>
 */
public record FileDownloadResponse(
        String path,
        Optional<byte[]> content,
        Optional<String> error
) {
    public static FileDownloadResponse success(String path, byte[] content) {
        return new FileDownloadResponse(path, Optional.ofNullable(content), Optional.empty());
    }

    public static FileDownloadResponse failure(String path, String error) {
        return new FileDownloadResponse(path, Optional.empty(), Optional.ofNullable(error));
    }

    public boolean isSuccess() { return content.isPresent() && error.isEmpty(); }
}
