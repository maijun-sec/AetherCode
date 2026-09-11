package org.aethercode.deepagents.langchain_compat.middleware;

import java.util.List;
import java.util.Map;

/**
 * LangChain-compatible hook config annotation / builder.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.types.hook_config}. In Python
 * this is a {@code TypedDict} that hooks can declare to receive
 * typed configuration. The Java port is a record that holds
 * arbitrary config data so middleware subclasses can read the
 * fields they care about.</p>
 */
public record HookConfig(
        String name,
        List<String> canJumpTo,
        boolean canAbort,
        Map<String, Object> metadata) {

    public HookConfig {
        canJumpTo = canJumpTo == null ? List.of() : List.copyOf(canJumpTo);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static HookConfig empty() {
        return new HookConfig(null, null, false, null);
    }

    public static HookConfig of(String name) {
        return new HookConfig(name, null, false, null);
    }
}
