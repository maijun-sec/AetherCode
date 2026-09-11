package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configurable model middleware (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code.configurable_model}
 * module. The Java port exposes the small surface used by the agent
 * graph factory when swapping the active model; the full implementation
 * lands with the deepagents-core middleware port.</p>
 */
public final class ConfigurableModel {
    private ConfigurableModel() {}

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurableModel.class);

    /** Resolve the model spec for a context. */
    public static String resolveModelSpec(Object context) {
        if (context instanceof Map<?, ?> m) {
            Object spec = m.get("model");
            if (spec instanceof String s && !s.isEmpty()) return s;
        }
        return null;
    }

    /** Resolve the per-run model parameters. */
    public static Map<String, Object> resolveModelParams(Object context) {
        if (context instanceof Map<?, ?> m) {
            Object params = m.get("model_params");
            if (params instanceof Map<?, ?> p) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : p.entrySet()) {
                    if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
                }
                return out;
            }
        }
        return Map.of();
    }
}
