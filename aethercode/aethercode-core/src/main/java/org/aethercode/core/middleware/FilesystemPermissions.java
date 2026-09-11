package org.aethercode.core.middleware;

/**
 * Backward-compatible re-export of {@link FilesystemPermission}.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.permissions} (3-line re-export
 * module). The original Python code re-exports
 * {@code FilesystemPermission} from {@code deepagents.middleware.filesystem}
 * for callers that imported it from the old path. The Java port keeps
 * the same name on the {@code FilesystemPermission} record directly
 * and exposes a small interface here so legacy imports keep
 * compiling.</p>
 */
public final class FilesystemPermissions {
    private FilesystemPermissions() {}

    /** Type alias for {@link FilesystemPermission}. */
    public static final Class<FilesystemPermission> PERMISSION_CLASS = FilesystemPermission.class;
}
