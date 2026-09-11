package org.aethercode.code.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Plugin JSON helpers (stub).
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.plugins.json} module. The Java port exposes
 * the small set of JSON helpers plugin adapters use; the full
 * schema-aware loader lands with the deepagents-code.plugins
 * subdirectory port.</p>
 */
public final class PluginJson {
    private PluginJson() {}

    private static final Logger LOG = LoggerFactory.getLogger(PluginJson.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Read a JSON file into a Map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(Path path) {
        if (path == null || !Files.exists(path)) return Map.of();
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return MAPPER.readValue(text, Map.class);
        } catch (IOException e) {
            LOG.warn("Could not read {}: {}", path, e.toString());
            return Map.of();
        }
    }

    /** Write a Map to a JSON file. */
    public static boolean write(Path path, Map<String, Object> data) {
        if (path == null) return false;
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, MAPPER.writeValueAsString(data) + "\n",
                    StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            LOG.warn("Could not write {}: {}", path, e.toString());
            return false;
        }
    }

    /** Narrow a value to a string-keyed map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> jsonObject(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k) {
                    out.put(k, e.getValue());
                }
            }
            return out;
        }
        return new java.util.LinkedHashMap<>();
    }
}
