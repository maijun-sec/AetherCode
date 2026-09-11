package org.aethercode.code.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plugin mini-JSON parser (stub).
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.plugins.mini_json} module. The Java port wraps
 * Jackson's {@link ObjectMapper#readValue(String, Class)} call so plugin
 * adapters can pass a config blob without depending on Jackson
 * directly.</p>
 */
public final class PluginMiniJson {
    private PluginMiniJson() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Parse a JSON blob into an Object (Map for objects, List for arrays). */
    public static Object parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (Exception e) {
            throw new RuntimeException("Plugin mini-json parse failed: " + e.getMessage(), e);
        }
    }
}
