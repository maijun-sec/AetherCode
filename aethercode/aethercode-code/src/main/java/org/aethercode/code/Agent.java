package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Top-level entry point for the agent graph factory.
 *
 * <p>Java-native port of the Python {@code deepagents_code.agent} module.
 * The Java port exposes the public surface used by {@link Main} and
 * the ACP server: the {@link #createCliAgent(Map)} entry point and the
 * approval-mode / interrupt helpers.</p>
 */
public final class Agent {
    private Agent() {}

    private static final Logger LOG = LoggerFactory.getLogger(Agent.class);

    /** Build the CLI agent graph with the given options. */
    public static Object createCliAgent(Map<String, Object> options) {
        // The full port wires the deepagents-core graph factory; the Java
        // port returns a placeholder until the deepagents-core wiring lands.
        LOG.info("createCliAgent called (stub); options keys: {}",
                options == null ? List.of() : List.copyOf(options.keySet()));
        return new Object();
    }

    /** Look up the approval mode for a context. */
    public static ApprovalMode.Mode resolveApprovalMode(Object context, Object store) {
        if (context == null) {
            return ApprovalMode.Mode.MANUAL;
        }
        Object mode = (context instanceof Map<?, ?> m) ? m.get("approval_mode") : null;
        if (mode == null && store != null) {
            String key = (context instanceof Map<?, ?> m) ? (String) m.get("approval_mode_key") : null;
            mode = ApprovalMode.readApprovalModeFromStore(store, key);
        }
        return ApprovalMode.Mode.coerce(mode);
    }

    /** Decide whether a tool call should interrupt. */
    public static boolean shouldInterruptToolCall(Object request) {
        // The full port uses the langgraph interrupt predicate; the Java
        // port returns true when the request is a tool-call request shape.
        return request != null;
    }
}
