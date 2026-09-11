package org.aethercode.code.plugins;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plugin variable substitution (stub).
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.plugins.substitution} module. The Java port
 * exposes a single helper that resolves {@code ${VAR}} references in
 * plugin-supplied config; the full templating engine lands with the
 * deepagents-code.plugins subdirectory port.</p>
 */
public final class PluginSubstitution {
    private PluginSubstitution() {}

    /** Substitute {@code ${VAR}} references in a string. */
    public static String substitute(String text, Map<String, String> env) {
        if (text == null || env == null) return text;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                int end = text.indexOf('}', i + 2);
                if (end > i + 2) {
                    String key = text.substring(i + 2, end);
                    String value = env.get(key);
                    out.append(value == null ? "" : value);
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Build the environment map for a plugin: includes the plugin's
     * root, data directory, and the project directory if known.
     */
    public static Map<String, String> pluginEnvironment(Path pluginRoot, Path pluginDataDir, Path projectDir) {
        Map<String, String> env = new LinkedHashMap<>();
        if (pluginRoot != null) {
            env.put("PLUGIN_ROOT", pluginRoot.toAbsolutePath().toString());
        }
        if (pluginDataDir != null) {
            env.put("PLUGIN_DATA", pluginDataDir.toAbsolutePath().toString());
        }
        if (projectDir != null) {
            env.put("PROJECT_DIR", projectDir.toAbsolutePath().toString());
        }
        return env;
    }

    /**
     * Substitute {@code ${VAR}} references in a JSON-shaped value.
     * Walks maps and lists; passes primitives through.
     */
    public static Object substituteJson(Object value, Path pluginRoot, Path pluginDataDir, Path projectDir) {
        Map<String, String> env = pluginEnvironment(pluginRoot, pluginDataDir, projectDir);
        return substituteIn(value, env);
    }

    @SuppressWarnings("unchecked")
    private static Object substituteIn(Object value, Map<String, String> env) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), substituteIn(e.getValue(), env));
            }
            return out;
        }
        if (value instanceof java.util.List<?> l) {
            java.util.List<Object> out = new java.util.ArrayList<>(l.size());
            for (Object item : l) out.add(substituteIn(item, env));
            return out;
        }
        if (value instanceof String s) return substitute(s, env);
        return value;
    }
}
