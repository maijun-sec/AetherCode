package org.aethercode.core.fs.backend;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;

/**
 * Classify exceptions raised by backend file operations into the
 * standard {@link FileOperationError} codes.
 *
 * <p>Java-native port of deepagents
 * <code>_map_exception_to_standard_error</code>. The Python port's
 * classifier inspects both the exception class and the message
 * (e.g. {@code ValueError("path traversal detected")} →
 * {@code "invalid_path"}); the Java port relies on the
 * structured exception types (no message-sniffing) and only
 * falls back to {@code invalid_path} for {@code IllegalArgumentException}
 * because that is the runtime's standard shape for rejected
 * paths. Recursive causes are walked to handle wrapped
 * {@code FileSystemException} with errno {@code ELOOP}
 * (symlink loops).</p>
 *
 * <p>Returns {@code null} when the exception is unrecognized —
 * callers should treat that as "unmappable, surface verbatim".</p>
 */
public final class FileOperationErrorMapper {
    private FileOperationErrorMapper() {}

    /** Map an exception to a {@link FileOperationError} code, or
     *  {@code null} if unrecognized. */
    public static String mapToCode(Throwable exc) {
        if (exc == null) return null;
        if (exc instanceof NoSuchFileException) {
            return FileOperationError.FILE_NOT_FOUND_CODE;
        }
        if (isSymlinkLoopError(exc)) {
            return FileOperationError.INVALID_PATH_CODE;
        }
        if (exc instanceof AccessDeniedException) {
            return FileOperationError.PERMISSION_DENIED_CODE;
        }
        if (exc instanceof NotDirectoryException) {
            return FileOperationError.INVALID_PATH_CODE;
        }
        if (exc instanceof FileAlreadyExistsException) {
            return FileOperationError.INVALID_PATH_CODE;
        }
        if (exc instanceof IllegalArgumentException) {
            return FileOperationError.INVALID_PATH_CODE;
        }
        // Walk the cause chain in case the cause is mappable even
        // when the wrapper is not (e.g. a generic RuntimeError
        // around a real NoSuchFileException).
        Throwable cause = exc.getCause();
        if (cause != null && cause != exc) {
            String fromCause = mapToCode(cause);
            if (fromCause != null) return fromCause;
        }
        return null;
    }

    private static boolean isSymlinkLoopError(Throwable exc) {
        if (exc == null) return false;
        if (isEloopThrowable(exc)) return true;
        if (exc.getCause() != null && isSymlinkLoopError(exc.getCause())) return true;
        return false;
    }

    private static boolean isEloopThrowable(Throwable exc) {
        if (!(exc instanceof FileSystemException fse)) return false;
        String msg = exc.getMessage() == null ? "" : exc.getMessage().toLowerCase();
        if (msg.contains("too many levels of symbolic links")
                || msg.contains("symbolic link loop")) {
            return true;
        }
        // Java's FileSystemException doesn't expose errno directly,
        // so we can't replicate the ELOOP check. The Python port's
        // check is a string match; the Java port does the same.
        return msg.contains("eloop");
    }
}
