package org.aethercode.talon;

import org.aethercode.deepagents.langchain_compat.tools.BaseTool;
import org.aethercode.talon.channels.ChannelBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP configuration and tool loading for Talon.
 *
 * <p>Java-native port of {@code deepagents_talon.mcp}. The Python port
 * delegates to {@code deepagents_code.mcp_tools}; the Java port keeps the
 * same public surface but currently does not load real MCP tools because
 * the deepagents-code module is not in the build yet. It still honours
 * the configuration discovery paths and exposes the same constants so
 * callers (and the CLI) can rely on a stable surface.</p>
 */
public final class Mcp {

    private static final Logger log = LoggerFactory.getLogger(Mcp.class);

    /** Environment variable names checked for an explicit MCP config path. */
    public static final String[] MCP_CONFIG_ENV_KEYS = {
            "DEEPAGENTS_TALON_MCP_CONFIG", "MCP_CONFIG"};
    /** Environment variable name for the Talon workspace root. */
    public static final String WORKSPACE_ENV = "DEEPAGENTS_TALON_WORKSPACE";

    /** Path display table mirrored from deepagents_code.mcp_tools. */
    public record DiscoveryPath(String display, String label) {}

    /** Default discovery paths (lowest to highest precedence). */
    public static final List<DiscoveryPath> MCP_CONFIG_DISCOVERY_PATHS = List.of(
            new DiscoveryPath("~/.deepagents/.mcp.json", "user home"),
            new DiscoveryPath("<project-root>/.deepagents/.mcp.json", "project .deepagents/"),
            new DiscoveryPath("<project-root>/.mcp.json", "project root")
    );

    /** Loaded MCP tools and per-server load statuses. */
    public record McpTools(List<BaseTool> tools, List<ServerInfo> servers) {}

    /** Per-server load result surfaced to operators. */
    public record ServerInfo(String name, String error, List<BaseTool> tools) {
        public ServerInfo {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    private Mcp() {}

    /**
     * Return existing MCP config files in Deep Agents Code discovery order.
     */
    public static List<Path> discoverMcpConfigPaths(TalonConfig config) {
        Path projectRoot = projectContext(config);
        List<Path> found = new ArrayList<>();
        for (DiscoveryPath entry : MCP_CONFIG_DISCOVERY_PATHS) {
            Path resolved = resolveDiscoveryDisplayPath(entry.display(), projectRoot);
            if (Files.isRegularFile(resolved)) {
                found.add(resolved);
            }
        }
        return found;
    }

    /**
     * Load configured MCP tools for a Talon runtime.
     *
     * <p>The Java port currently returns an empty {@link McpTools} result;
     * MCP tool loading is expected to be wired up when the deepagents-code
     * module is ported.</p>
     */
    public static McpTools loadMcpTools(TalonConfig config) {
        String firstEnv = firstEnvValue(config.env());
        if (firstEnv != null) {
            log.debug("MCP config path candidate: {}", firstEnv);
        }
        return new McpTools(List.of(), List.of());
    }

    /** Print Deep Agents Code MCP config discovery paths. */
    public static void printMcpConfigPaths(TalonConfig config) {
        Path projectRoot = projectContext(config);
        List<Path> found = new ArrayList<>();
        for (Path p : discoverMcpConfigPaths(config)) {
            found.add(p.toAbsolutePath());
        }
        int width = 0;
        for (DiscoveryPath entry : MCP_CONFIG_DISCOVERY_PATHS) {
            width = Math.max(width, entry.display().length());
        }
        System.out.println("MCP config discovery paths (lowest to highest precedence):");
        for (DiscoveryPath entry : MCP_CONFIG_DISCOVERY_PATHS) {
            Path path = resolveDiscoveryDisplayPath(entry.display(), projectRoot);
            boolean present = found.contains(path.toAbsolutePath()) || isFile(path);
            String marker = present ? "found" : "missing";
            System.out.println("  [" + pad(marker, 7) + "]  "
                    + pad(entry.display(), width) + "  (" + entry.label() + ")");
        }
        System.out.println();
        System.out.println("<project-root> = nearest ancestor with `.git`, else current directory.");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String firstEnvValue(Map<String, String> env) {
        if (env != null) {
            for (String key : MCP_CONFIG_ENV_KEYS) {
                String value = env.get(key);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        for (String key : MCP_CONFIG_ENV_KEYS) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Path projectContext(TalonConfig config) {
        String raw = config.env().get(WORKSPACE_ENV);
        Path base = (raw == null || raw.isBlank())
                ? Path.of("").toAbsolutePath()
                : Path.of(raw).toAbsolutePath();
        return ChannelBase.outboundMediaRootFromEnv(config.env());
    }

    private static Path resolveDiscoveryDisplayPath(String display, Path projectRoot) {
        if (display.equals("~/.deepagents/.mcp.json")) {
            return Path.of(System.getProperty("user.home")).resolve(".deepagents/.mcp.json");
        }
        if (display.equals("<project-root>/.deepagents/.mcp.json")) {
            return projectRoot.resolve(".deepagents/.mcp.json");
        }
        if (display.equals("<project-root>/.mcp.json")) {
            return projectRoot.resolve(".mcp.json");
        }
        return Path.of(display).toAbsolutePath();
    }

    private static boolean isFile(Path path) {
        try {
            return Files.isRegularFile(path);
        } catch (SecurityException e) {
            log.warn("Could not inspect MCP config path {}", path, e);
            return false;
        }
    }

    private static String pad(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        StringBuilder out = new StringBuilder(value);
        while (out.length() < width) {
            out.append(' ');
        }
        return out.toString();
    }

    /**
     * Return the configured MCP config path (if any), used by the CLI.
     */
    public static Optional<String> configPathFromEnv(Map<String, String> env) {
        return Optional.ofNullable(firstEnvValue(env));
    }

    /**
     * Build a URI for a relative MCP config file under the operator's
     * home (test helper).
     */
    public static URI homeMcpUri() {
        return URI.create("file:" + Path.of(System.getProperty("user.home"))
                .resolve(".deepagents/.mcp.json").toString().replace("\\", "/"));
    }
}
