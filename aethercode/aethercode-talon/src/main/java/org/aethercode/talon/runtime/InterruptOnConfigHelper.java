package org.aethercode.talon.runtime;

import org.aethercode.deepagents.langchain_compat.middleware.InterruptOnConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Helpers for the {@code interrupt_on} env overlay that the Talon host
 * merges into the agent's HITL configuration.
 *
 * <p>Java-native port of the
 * {@code deepagents_talon.runtime.interrupt_on_with_env_overlay} and
 * related functions.</p>
 */
public final class InterruptOnConfigHelper {

    private InterruptOnConfigHelper() {}

    /**
     * Merge Talon's local tool approval env overlay into an
     * {@code interrupt_on} mapping.
     *
     * @return merged approval configuration, or {@code null} when neither
     *         source configures approval.
     */
    public static Map<String, Object> merge(
            Map<String, Object> interruptOn,
            Map<String, String> env) {
        Map<String, Object> overlay = interruptOnToolsFromEnv(env);
        if ((interruptOn == null || interruptOn.isEmpty()) && overlay.isEmpty()) {
            return null;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (interruptOn != null) {
            merged.putAll(interruptOn);
        }
        merged.putAll(overlay);
        return merged;
    }

    /**
     * Add the async-subagent tool names to the interrupt map when the
     * runtime has async subagents.
     */
    public static Map<String, Object> withAsyncSubagentDefaults(
            Map<String, Object> interruptOn, boolean hasAsyncSubagents) {
        if (!hasAsyncSubagents) {
            return interruptOn == null ? null : Map.copyOf(interruptOn);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (interruptOn != null) {
            merged.putAll(interruptOn);
        }
        for (String name : RuntimeEnv.ASYNC_SUBAGENT_TOOL_NAMES) {
            merged.putIfAbsent(name, InterruptOnConfig.of(true));
        }
        return merged;
    }

    /**
     * Parse the {@code DEEPAGENTS_TALON_INTERRUPT_ON_TOOLS} env var into
     * a {@code Map<String, Boolean>}.
     */
    public static Map<String, Object> interruptOnToolsFromEnv(Map<String, String> env) {
        if (env == null) {
            return Map.of();
        }
        String raw = env.get(RuntimeEnv.INTERRUPT_ON_TOOLS_ENV_KEY);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                out.put(name, Boolean.TRUE);
            }
        }
        return out;
    }

    /**
     * Read a positive integer env var, returning {@code null} when absent.
     */
    public static Integer positiveIntFromEnv(Map<String, String> env, String key) {
        if (env == null) {
            return null;
        }
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a positive integer", e);
        }
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
        return value;
    }
}
