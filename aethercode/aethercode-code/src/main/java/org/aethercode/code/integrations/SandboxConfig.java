package org.aethercode.code.integrations;

import java.util.List;
import java.util.Map;

/**
 * Sandbox provider configuration record.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.integrations.sandbox_config} module.</p>
 */
public record SandboxConfig(
        String name,
        String provider,
        Map<String, Object> options,
        List<String> allowedTools) {

    public SandboxConfig {
        options = options == null ? Map.of() : Map.copyOf(options);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }

    public static SandboxConfig of(String name, String provider) {
        return new SandboxConfig(name, provider, Map.of(), List.of());
    }
}
