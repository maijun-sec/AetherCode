package org.aethercode.deepagents.langchain_compat.middleware;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * LangChain-compatible Human-in-the-Loop middleware.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.HumanInTheLoopMiddleware}.
 * Pauses execution before configured tool calls and waits for an
 * approve / edit / reject decision from the user. The Java port
 * is a passthrough: the runtime is expected to look up the
 * configured tool names and short-circuit when an interrupt is
 * requested. The middleware records the configured tools under
 * {@link #interruptOnMap()} so callers can inspect them.</p>
 */
public class HumanInTheLoopMiddleware
        extends AgentMiddleware<AgentState, Object, ModelResponse> {

    private final Map<String, InterruptOnConfig> interruptOn;

    public HumanInTheLoopMiddleware(Map<String, InterruptOnConfig> interruptOn) {
        this.interruptOn = interruptOn == null ? Map.of() : Map.copyOf(interruptOn);
    }

    public HumanInTheLoopMiddleware() {
        this(Map.of());
    }

    @Override
    public String name() { return "HumanInTheLoopMiddleware"; }

    @Override
    public String description() {
        return "Pauses execution before configured tool calls for human review.";
    }

    /** Return the configured interrupt-on map. */
    public Map<String, InterruptOnConfig> interruptOnMap() {
        return interruptOn;
    }

    /** Return the set of tools that should trigger an interrupt. */
    public Set<String> configuredToolNames() {
        return interruptOn.keySet();
    }

    /**
     * Decide whether {@code toolName} should trigger an interrupt.
     * The default returns {@code true} when the tool is
     * configured; subclasses can override.
     */
    public boolean shouldInterrupt(String toolName, Map<String, Object> arguments) {
        Objects.requireNonNull(toolName, "toolName");
        return interruptOn.containsKey(toolName)
                && interruptOn.get(toolName).enabled();
    }

    @Override
    public AgentState beforeModel(AgentState state, Object runtime) {
        // Passthrough: the runtime's HITL integration looks at
        // interruptOn() to decide when to pause.
        return state;
    }

    /** Build from a map of {@code toolName -> boolean | map}. */
    @SuppressWarnings("unchecked")
    public static HumanInTheLoopMiddleware fromMap(Map<String, Object> raw) {
        Map<String, InterruptOnConfig> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean b) {
                converted.put(e.getKey(), InterruptOnConfig.of(b));
            } else if (v instanceof Map<?, ?> m) {
                converted.put(e.getKey(), InterruptOnConfig.fromMap((Map<String, Object>) m));
            }
        }
        return new HumanInTheLoopMiddleware(converted);
    }
}
