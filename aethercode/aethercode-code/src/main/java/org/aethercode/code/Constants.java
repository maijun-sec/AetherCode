package org.aethercode.code;

import java.util.List;
import java.util.Set;

/**
 * Lightweight shared constants for the app.
 *
 * <p>This class is intentionally dependency-free (no third-party imports, no
 * sibling-class imports) so any other class — including the startup-critical
 * {@link Main} and the heavy {@link Agent} — can reference it without
 * triggering a chain of expensive imports. Java-native port of the Python
 * {@code deepagents_code._constants} module.</p>
 */
public final class Constants {
    private Constants() {}

    /** Default agent / assistant identifier when no {@code -a} flag is given. */
    public static final String DEFAULT_AGENT_NAME = "agent";

    /**
     * Mirror of the SDK's {@code FsToolName} literal members. Hardcoded here
     * rather than derived from {@code deepagents} because {@code deepagents}
     * must not be imported on the arg-parsing hot path.
     */
    public static final Set<String> FS_TOOL_NAMES = Set.of(
            "ls", "read_file", "write_file", "edit_file", "delete", "glob", "grep", "execute");

    /** Maximum time to drain Hooks v2 {@code SessionEnd} during session teardown. */
    public static final double SESSION_END_DRAIN_TIMEOUT_SECONDS = 2.0;

    /** Default {@code RubricMiddleware.max_iterations}, shown without importing the SDK. */
    public static final int SDK_DEFAULT_RUBRIC_MAX_ITERATIONS = 3;

    /** Prefix used to infer Fireworks from fully-qualified IDs. */
    public static final String FIREWORKS_PROVIDER_ID_PREFIX = "accounts/fireworks/";

    /** Model and router ID prefixes used for stripping and classification. */
    public static final List<String> FIREWORKS_MODEL_ID_PREFIXES = List.of(
            "accounts/fireworks/models/",
            "accounts/fireworks/routers/");

    /** User-facing reconnect guidance shown for an MCP server that was optimistically re-enabled. */
    public static final String MCP_REENABLED_PENDING_ERROR = "Re-enabled — press Ctrl+R to load.";

    /** Mirror of the SDK's {@code deepagents.backends.protocol.FILE_NOT_FOUND} sentinel. */
    public static final String FILE_NOT_FOUND = "file_not_found";

    /** Prefix for synthetic human messages (e.g. interrupt cancellation notices). */
    public static final String SYSTEM_MESSAGE_PREFIX = "[SYSTEM]";
}
