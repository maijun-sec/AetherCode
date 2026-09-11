package org.aethercode.code.plugins;

import java.util.List;
import java.util.Map;

/**
 * Plugin record types.
 *
 * <p>Java-native port of the Python {@code deepagents_code.plugins.models}
 * module. The Java port exposes the small set of records the plugin
 * lifecycle and CLI use; the full plugin loading/discovery lands with
 * the deepagents-code.plugins subdirectory port.</p>
 */
public final class PluginModels {
    private PluginModels() {}

    /** A discovered plugin. */
    public record PluginInstance(
            String id,
            String description,
            String version,
            boolean enabled,
            String marketplace,
            String path) {

        /** Plugin id accessor (alias for {@link #id()} so adapters can
         *  use either naming). */
        public String pluginId() { return id; }

        /** Plugin root directory. */
        public java.nio.file.Path root() {
            return path == null ? null : java.nio.file.Path.of(path);
        }

        /** Plugin data directory (for cached state). */
        public java.nio.file.Path dataDir() {
            return root() == null ? null : root().resolve(".plugin-data");
        }

        /** The plugin's inventory — paths to MCP files, skills, hooks. */
        public Inventory inventory() { return new Inventory(java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of()); }

        /** The plugin's manifest. */
        public Manifest manifest() { return new Manifest(java.util.Map.of(), java.util.Map.of()); }
    }

    /** Inventory: the plugin's contribution to MCP/skills/hooks. */
    public record Inventory(
            java.util.List<java.nio.file.Path> mcpFiles,
            java.util.List<java.nio.file.Path> skills,
            java.util.List<java.nio.file.Path> hooks,
            java.util.List<java.nio.file.Path> hookFiles) {
    }

    /** The plugin's manifest. */
    public record Manifest(
            java.util.Map<String, Object> inlineMcp,
            java.util.Map<String, Object> inlineHooks) {
        public Manifest(java.util.List<?> empty) { this(java.util.Map.of(), java.util.Map.of()); }
    }

    /** Result of plugin discovery. */
    public record PluginDiscoveryResult(
            List<PluginInstance> discovered,
            List<String> errors) {
        /** Alias used by some adapter code. */
        public List<PluginInstance> plugins() { return discovered; }
    }

    /** A plugin command. */
    public record PluginCommand(
            String pluginId,
            String name,
            String description,
            Map<String, Object> args) {
    }

    /** A plugin model. */
    public record PluginModel(
            String pluginId,
            String provider,
            String model) {
    }

    /** A marketplace source. */
    public sealed interface PluginMarketplace
            permits LocalMarketplace, FileMarketplace, GitMarketplace, UrlMarketplace {
        String name();
        List<MarketplacePluginEntry> plugins();
    }

    public record LocalMarketplace(String name, String path) implements PluginMarketplace {
        @Override public List<MarketplacePluginEntry> plugins() { return List.of(); }
    }
    public record FileMarketplace(String name, String path) implements PluginMarketplace {
        @Override public List<MarketplacePluginEntry> plugins() { return List.of(); }
    }
    public record GitMarketplace(String name, String url, String ref) implements PluginMarketplace {
        @Override public List<MarketplacePluginEntry> plugins() { return List.of(); }
    }
    public record UrlMarketplace(String name, String url) implements PluginMarketplace {
        @Override public List<MarketplacePluginEntry> plugins() { return List.of(); }
    }

    /** A marketplace plugin entry. */
    public record MarketplacePluginEntry(
            String name,
            String description,
            String version,
            String homepage,
            String displayName,
            Object author,
            java.util.List<?> tools,
            String license) {
        public MarketplacePluginEntry(String name, String description) {
            this(name, description, null, null, null, null, java.util.List.of(), null);
        }
        public MarketplacePluginEntry(String name, String description, String version, String homepage) {
            this(name, description, version, homepage, null, null, java.util.List.of(), null);
        }
    }

    /** A marketplace record. */
    public record MarketplaceRecord(
            String name,
            String source,
            String installLocation) {
    }
}
