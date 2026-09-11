package org.aethercode.talon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.deepagents.middleware.AsyncSubAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Async subagent configuration loading for Talon.
 *
 * <p>Java-native port of
 * {@code deepagents_talon.async_subagents.load_async_subagents}. Reads the
 * {@code [async_subagents]} section of the operator's
 * {@code ~/.deepagents/config.toml} file and materializes a list of
 * {@link AsyncSubAgent} records.</p>
 */
public final class AsyncSubagents {

    private static final Logger log = LoggerFactory.getLogger(AsyncSubagents.class);

    private AsyncSubagents() {}

    /** Default config file path (under the user home). */
    public static final Path DEFAULT_CONFIG_PATH =
            Path.of(System.getProperty("user.home"))
                    .resolve(".deepagents")
                    .resolve("config.toml");

    /**
     * Load async subagent definitions from {@code config.toml}.
     *
     * @param configPath path to config file. Defaults to
     *                   {@code ~/.deepagents/config.toml}.
     * @return list of async subagent specs, or empty when absent/invalid.
     */
    public static List<AsyncSubAgent> loadAsyncSubagents(Path configPath) {
        Path path = configPath != null ? configPath : DEFAULT_CONFIG_PATH;
        if (!Files.exists(path)) {
            return List.of();
        }

        Map<String, Object> data;
        try {
            String content = Files.readString(path);
            data = TomlUtils.parse(content);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read async subagents from {}: {}", path, e.toString());
            return List.of();
        }
        Object section = data.get("async_subagents");
        if (!(section instanceof Map<?, ?> rawMap)) {
            return List.of();
        }
        Map<String, Object> sectionMap = castMap(rawMap);

        List<AsyncSubAgent> agents = new ArrayList<>();
        for (Map.Entry<String, Object> entry : sectionMap.entrySet()) {
            AsyncSubAgent agent = parseAsyncSubagent(entry.getKey(), entry.getValue());
            if (agent != null) {
                agents.add(agent);
            }
        }
        return agents;
    }

    /** Convenience overload: defaults the path. */
    public static List<AsyncSubAgent> loadAsyncSubagents() {
        return loadAsyncSubagents(null);
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static AsyncSubAgent parseAsyncSubagent(Object name, Object spec) {
        if (!(name instanceof String sName)) {
            log.warn("Skipping async subagent with non-string name: {}", name);
            return null;
        }
        if (!(spec instanceof Map<?, ?> rawMap)) {
            log.warn("Skipping async subagent '{}': expected a table", sName);
            return null;
        }
        Map<String, Object> data = castMap(rawMap);

        if (!data.containsKey("description") || !data.containsKey("graph_id")) {
            log.warn("Skipping async subagent '{}': missing fields {}",
                    sName,
                    new ArrayList<>(List.of("description", "graph_id"))
                            .stream()
                            .filter(k -> !data.containsKey(k))
                            .toList());
            return null;
        }
        Object description = data.get("description");
        Object graphId = data.get("graph_id");
        if (!(description instanceof String) || !(graphId instanceof String)) {
            log.warn("Skipping async subagent '{}': description and graph_id must be strings",
                    sName);
            return null;
        }
        AsyncSubAgent.Builder builder = AsyncSubAgent.builder(
                sName, (String) description, (String) graphId);
        Object url = data.get("url");
        if (url instanceof String sUrl) {
            builder.url(sUrl);
        }
        Object headers = data.get("headers");
        if (headers instanceof Map<?, ?> rawHeaders) {
            Map<String, String> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> h : rawHeaders.entrySet()) {
                if (h.getKey() instanceof String k
                        && h.getValue() instanceof String v) {
                    map.put(k, v);
                }
            }
            builder.headers(map);
        }
        return builder.build();
    }

    private static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() instanceof String k) {
                out.put(k, e.getValue());
            }
        }
        return out;
    }
}
