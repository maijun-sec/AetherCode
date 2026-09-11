package org.aethercode.core.middleware;
import org.aethercode.core.runtime.Message.ToolMessage;

/**
 * Thrown when a filesystem tool is called with a path that the
 * permission rules reject.
 *
 * <p>Java-native port of the
 * {@code deepagents.middleware.filesystem} permission-denied
 * error path. The runtime catches this and converts it to a
 * {@code ToolMessage} the model can see.</p>
 */
public class FilesystemPermissionDeniedException extends RuntimeException {
    public FilesystemPermissionDeniedException(String message) { super(message); }
}
