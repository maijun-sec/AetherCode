package org.aethercode.core.fs.backend;

/**
 * Standardised error codes for file upload/download operations.
 *
 * <p>Mirror of the deepagents <code>FileOperationError</code> literal
 * type. These are the recoverable, agent-readable categories.</p>
 */
public enum FileOperationError {
    FILE_NOT_FOUND   ("file_not_found"),
    PERMISSION_DENIED("permission_denied"),
    IS_DIRECTORY     ("is_directory"),
    INVALID_PATH     ("invalid_path");

    private final String code;

    FileOperationError(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static final String FILE_NOT_FOUND_CODE   = "file_not_found";
    public static final String PERMISSION_DENIED_CODE = "permission_denied";
    public static final String IS_DIRECTORY_CODE     = "is_directory";
    public static final String INVALID_PATH_CODE     = "invalid_path";
}
