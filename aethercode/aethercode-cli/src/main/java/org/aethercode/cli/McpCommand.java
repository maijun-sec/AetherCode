package org.aethercode.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;

/**
 * parent command for {@code aethercode mcp ...} subcommands. Holds the
 * shared {@code --config} option so each subcommand can resolve the mcp.json
 * path consistently.
 */
@Command(
        name = "mcp",
        description = "MCP server utilities.",
        subcommands = { McpAuthCommand.class }
)
public class McpCommand {

    @Spec
    CommandSpec spec;

    @Option(names = {"--config", "-c"}, description = "Path to mcp.json. Default: .aethercode/mcp.json in cwd.")
    Path config;

    Path configPath() {
        if (config != null) return config;
        return Path.of("").toAbsolutePath().resolve(".aethercode").resolve("mcp.json");
    }
}
