package org.aethercode.code.plugins.adapters;

import org.aethercode.code.plugins.PluginJson;
import org.aethercode.code.plugins.PluginModels;
import org.aethercode.code.plugins.PluginSubstitution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adapter from plugin MCP declarations to dcode MCP config
 * dictionaries.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.plugins.adapters.mcp} module.</p>
 */
public final class PluginMcpAdapter {
    private static final Logger LOGGER = Logger.getLogger(PluginMcpAdapter.class.getName());
    private static final Set<String> UNSUPPORTED_SUFFIXES = Set.of(".mcpb", ".dxt");
    private static final int NAME_PART_LENGTH = 48;

    private PluginMcpAdapter() {}

    /**
     * Triple of (label, scoped name, needs login).
     */
    public record McpServerEntry(String label, String scopedName, boolean needsLogin) {}

    /**
     * Namespace a plugin-declared MCP server's name under its plugin
     * id.
     */
    public static String scopedMcpServerName(String pluginId, String serverName) {
        String pluginPart = safeNamePart(pluginId);
        String serverPart = safeNamePart(serverName);
        return "plugin__" + pluginPart + "__" + serverPart;
    }

    /**
     * List plugin MCP servers as (label, scoped_name, needs_login)
     * tuples.
     */
    public static List<McpServerEntry> pluginMcpServerEntries(PluginModels.PluginInstance plugin) {
        Map<String, Object> servers = new LinkedHashMap<>();
        for (Path path : plugin.inventory().mcpFiles()) {
            if (UNSUPPORTED_SUFFIXES.stream().anyMatch(s -> path.toString().endsWith(s))) continue;
            servers.putAll(loadServerMap(path));
        }
        if (plugin.manifest() != null && !plugin.manifest().inlineMcp().isEmpty()) {
            servers.putAll(extractServerMap(plugin.manifest().inlineMcp()));
        }
        List<McpServerEntry> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Object> e : servers.entrySet()) {
            if (!(e.getKey() instanceof String name) || !seen.add(name)) continue;
            out.add(new McpServerEntry(
                    name,
                    scopedMcpServerName(plugin.pluginId(), name),
                    needsLogin(e.getValue())));
        }
        return out;
    }

    /** Build MCP config layers for enabled plugins. */
    public static List<Map<String, Object>> pluginMcpConfigs(
            List<PluginModels.PluginInstance> plugins, Path projectDir) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PluginModels.PluginInstance plugin : plugins) {
            try {
                Files.createDirectories(plugin.dataDir());
            } catch (IOException exc) {
                LOGGER.log(Level.WARNING, "Could not create plugin data dir for "
                        + plugin.pluginId() + ": " + exc.getMessage(), exc);
            }
            Map<String, Object> servers = new LinkedHashMap<>();
            for (Path path : plugin.inventory().mcpFiles()) {
                if (UNSUPPORTED_SUFFIXES.stream().anyMatch(s -> path.toString().endsWith(s))) continue;
                servers.putAll(loadServerMap(path));
            }
            if (plugin.manifest() != null && !plugin.manifest().inlineMcp().isEmpty()) {
                servers.putAll(extractServerMap(plugin.manifest().inlineMcp()));
            }
            Map<String, Object> scoped = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : servers.entrySet()) {
                if (!(e.getKey() instanceof String name)) continue;
                String scopedName = scopedMcpServerName(plugin.pluginId(), name);
                scoped.put(scopedName, normalizeServer(e.getValue(), plugin, projectDir));
            }
            if (!scoped.isEmpty()) {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("mcpServers", scoped);
                out.add(wrapper);
            }
        }
        return out;
    }

    private static boolean needsLogin(Object server) {
        if (!(server instanceof Map<?, ?> m)) return false;
        Object type = m.get("type");
        if ("http".equals(type) || "sse".equals(type)) return true;
        return m.get("url") instanceof String;
    }

    private static Object normalizeServer(Object server, PluginModels.PluginInstance plugin,
                                          Path projectDir) {
        Object normalized = PluginSubstitution.substituteJson(server, plugin.root(),
                plugin.dataDir(), projectDir);
        if (normalized instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            Map<String, Object> copy = new LinkedHashMap<>(typed);
            Object cwd = copy.get("cwd");
            if (cwd instanceof String cwdStr && !cwdStr.isEmpty() && !Path.of(cwdStr).isAbsolute()) {
                copy.put("cwd", plugin.root().resolve(cwdStr).toAbsolutePath().toString());
            }
            Map<String, String> pluginEnv = PluginSubstitution.pluginEnvironment(
                    plugin.root(), plugin.dataDir(), projectDir);
            Object env = copy.get("env");
            Map<String, String> merged = new LinkedHashMap<>(pluginEnv);
            if (env instanceof Map<?, ?> envMap) {
                for (Map.Entry<?, ?> e : envMap.entrySet()) {
                    if (e.getKey() instanceof String k && e.getValue() instanceof String v) {
                        merged.put(k, v);
                    }
                }
            }
            copy.put("env", merged);
            return copy;
        }
        return normalized;
    }

    private static Map<String, Object> extractServerMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return Map.of();
        Object wrapped = m.get("mcpServers");
        if (wrapped instanceof Map<?, ?> w) return PluginJson.jsonObject(w);
        Object codexWrapped = m.get("mcp_servers");
        if (codexWrapped instanceof Map<?, ?> w) return PluginJson.jsonObject(w);
        return PluginJson.jsonObject(raw);
    }

    private static Map<String, Object> loadServerMap(Path path) {
        try {
            String text = Files.readString(path);
            Object decoded = org.aethercode.code.plugins.PluginMiniJson.parse(text);
            return extractServerMap(decoded);
        } catch (IOException | RuntimeException exc) {
            LOGGER.log(Level.WARNING, "Skipping plugin MCP config " + path + ": " + exc.getMessage());
            return Map.of();
        }
    }

    private static String safeNamePart(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (sanitized.equals(value) && !sanitized.isEmpty() && sanitized.length() <= NAME_PART_LENGTH) {
            return sanitized;
        }
        String digest = sha256Prefix(value, 8);
        String prefix = sanitized.length() > NAME_PART_LENGTH
                ? sanitized.substring(0, NAME_PART_LENGTH) : (sanitized.isEmpty() ? "unnamed" : sanitized);
        return prefix + "_" + digest;
    }

    private static String sha256Prefix(String input, int hexChars) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
                if (sb.length() >= hexChars) break;
            }
            return sb.substring(0, hexChars);
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
