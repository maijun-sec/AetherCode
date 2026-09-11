package org.aethercode.core.middleware;
import org.aethercode.core.runtime.Message.ToolMessage;

/**
 * Thrown by {@link FilesystemPathValidator} when a path violates the
 * virtual-path rules (traversal, tilde, Windows drive prefix, or
 * an out-of-list prefix). The message is the user-facing string the
 * Python port formats (starts with {@code "Error: "} so the runtime
 * surfaces it as a failed ToolMessage).
 */
public class FilesystemPathTraversalException extends RuntimeException {
    public FilesystemPathTraversalException(String message) { super(message); }
}
