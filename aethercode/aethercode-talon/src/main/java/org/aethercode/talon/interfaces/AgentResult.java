package org.aethercode.talon.interfaces;

import java.util.Map;

/**
 * Agent invocation result returned to the host.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.AgentResult}.</p>
 */
public record AgentResult(
        String text,
        Map<String, Object> metadata) {

    public AgentResult {
        text = text == null ? "" : text;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public AgentResult(String text) {
        this(text, Map.of());
    }
}
