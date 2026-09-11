package org.aethercode.acp;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LangGraph state snapshot, returned by
 * {@link StreamingStateGraph#agetState}.
 *
 * <p>Mirrors {@code langgraph.types.StateSnapshot}. The Java
 * port keeps the surface small: a values map, the next-node
 * list, an optional config, and an optional interrupts list.</p>
 */
public record StateSnapshot(
        Map<String, Object> values,
        List<String> next,
        Optional<Map<String, Object>> config,
        Optional<List<Interrupt>> interrupts) {

    public StateSnapshot {
        values = values == null ? Map.of() : Map.copyOf(values);
        next = next == null ? List.of() : List.copyOf(next);
        config = config == null ? Optional.empty() : config.map(Map::copyOf);
        interrupts = interrupts == null ? Optional.empty() : interrupts;
    }

    /**
     * Pending interrupt. Mirrors {@code langgraph.types.Interrupt}.
     */
    public record Interrupt(String id, Object value) {
    }
}
