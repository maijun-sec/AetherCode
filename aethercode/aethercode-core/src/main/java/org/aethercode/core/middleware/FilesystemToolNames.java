package org.aethercode.core.middleware;

import java.util.Set;

/**
 * Constants for the filesystem middleware tool names and tool
 * path arguments. Mirrors
 * {@code deepagents.middleware._fs_interrupt._FS_TOOL_PATH_ARGS}
 * so the rest of the filesystem-middleware surface (and the
 * interrupt-config glue) can share one source of truth.</p>
 */
public final class FilesystemToolNames {
    private FilesystemToolNames() {}

    public static final String LS = "ls";
    public static final String READ_FILE = "read_file";
    public static final String WRITE_FILE = "write_file";
    public static final String EDIT_FILE = "edit_file";
    public static final String DELETE = "delete";
    public static final String GLOB = "glob";
    public static final String GREP = "grep";
    public static final String EXECUTE = "execute";

    public static final Set<String> ALL = Set.of(
            LS, READ_FILE, WRITE_FILE, EDIT_FILE, DELETE, GLOB, GREP, EXECUTE);
}
