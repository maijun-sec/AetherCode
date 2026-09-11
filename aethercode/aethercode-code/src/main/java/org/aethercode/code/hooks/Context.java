package org.aethercode.code.hooks;

import java.util.List;
import java.util.Map;

/**
 * Helpers for attaching Hooks v2 session identity to graph context.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.context} module. The graph context
 * carries a {@code hooks_snapshot_id} and the set of server-owned
 * events the client runtime wants emitted; this helper stamps both
 * onto a mutable per-run context map.</p>
 */
public final class Context {

    private Context() {}

    /**
     * Attach Hooks v2 snapshot identity and server event gates to
     * {@code context}.
     *
     * @param context   mutable per-run graph context
     * @param runtime   session hooks runtime, or {@code null} when
     *                  hooks are unavailable
     * @param promptId  optional per-turn prompt id
     * @return the same context map, updated in place
     */
    public static Map<String, Object> applyHooksContext(
            Map<String, Object> context,
            Runtime.HooksRuntime runtime,
            String promptId) {
        if (context == null) return null;
        if (runtime == null) {
            context.remove("hooks_snapshot_id");
            context.remove("hooks_server_events");
        } else {
            context.put("hooks_snapshot_id", runtime.snapshotId());
            context.put("hooks_server_events", List.copyOf(runtime.configuredServerEvents()));
        }
        if (promptId != null) {
            context.put("prompt_id", promptId);
        } else {
            context.remove("prompt_id");
        }
        return context;
    }
}
