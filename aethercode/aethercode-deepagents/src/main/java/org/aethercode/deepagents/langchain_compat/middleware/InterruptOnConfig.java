package org.aethercode.deepagents.langchain_compat.middleware;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * LangChain-compatible interrupt-on config.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.InterruptOnConfig}. Maps a
 * tool name to a boolean (interrupt before/after the call) or
 * to a config map ({@code allowedDecisions},
 * {@code description}, {@code argsValidator}, etc.).</p>
 */
public final class InterruptOnConfig {
    private final boolean enabled;
    private final Set<String> allowedDecisions;
    private final String description;
    private final List<String> argsValidator;

    public InterruptOnConfig(boolean enabled,
                              Set<String> allowedDecisions,
                              String description,
                              List<String> argsValidator) {
        this.enabled = enabled;
        this.allowedDecisions = allowedDecisions == null
                ? Set.of("approve", "edit", "reject")
                : Set.copyOf(allowedDecisions);
        this.description = description;
        this.argsValidator = argsValidator == null ? List.of() : List.copyOf(argsValidator);
    }

    public static InterruptOnConfig of(boolean enabled) {
        return new InterruptOnConfig(enabled, null, null, null);
    }

    @SuppressWarnings("unchecked")
    public static InterruptOnConfig fromMap(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        boolean enabled = (Boolean) map.getOrDefault("enabled", true);
        Set<String> allowed = map.get("allowed_decisions") instanceof List<?>
                ? Set.copyOf((List<String>) map.get("allowed_decisions"))
                : null;
        String description = (String) map.get("description");
        List<String> argsValidator = map.get("args_validator") instanceof List<?>
                ? List.copyOf((List<String>) map.get("args_validator"))
                : null;
        return new InterruptOnConfig(enabled, allowed, description, argsValidator);
    }

    public boolean enabled() { return enabled; }
    public Set<String> allowedDecisions() { return allowedDecisions; }
    public String description() { return description; }
    public List<String> argsValidator() { return argsValidator; }
}
