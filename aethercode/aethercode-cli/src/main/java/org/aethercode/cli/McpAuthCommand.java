package org.aethercode.cli;

import org.aethercode.mcp.McpAuthOrchestrator;
import org.aethercode.mcp.McpAuthOrchestrator.ServerAuth;
import org.aethercode.mcp.McpOAuthFlow;
import org.aethercode.mcp.auth.BrowserLauncher;
import org.aethercode.mcp.auth.OAuthCallbackServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * {@code aethercode mcp auth <server>} subcommand. Runs the full OAuth
 * dance for an MCP server whose mcp.json entry has an {@code auth} block.
 */
@Command(
        name = "auth",
        description = "Run the OAuth dance for an MCP server. Requires 'auth' in the server's mcp.json entry."
)
public class McpAuthCommand implements Callable<Integer> {

    @ParentCommand
    McpCommand parent;

    @Parameters(index = "0", description = "MCP server name (must appear in mcp.json's mcpServers map).")
    String serverName;

    @Override
    public Integer call() throws Exception {
        Path cfg = parent.configPath();
        Path tokens = cfg.getParent().resolve("mcp-tokens.json");
        McpAuthOrchestrator orch = new McpAuthOrchestrator(cfg, tokens);
        if (!orch.tryAcquireAuthSlot(serverName)) {
            long remain = orch.rateLimiter().remainingMs(serverName) / 1000;
            System.err.println("rate-limited: another auth for '" + serverName + "' is still cooling down ("
                    + remain + "s left). Re-run later or clear the cooldown via the API.");
            return 3;
        }
        ServerAuth auth = orch.loadAuthConfig(serverName);
        if (auth == null) {
            System.err.println("no 'auth' block for server '" + serverName + "' in " + cfg);
            return 1;
        }
        System.out.println("auth endpoint:    " + auth.authEndpoint());
        System.out.println("token endpoint:   " + auth.tokenEndpoint());
        System.out.println("client id:        " + auth.clientId());
        System.out.println("scope:            " + auth.scope());
        System.out.println("callback:         " + auth.redirectUri());

        McpOAuthFlow flow = new McpOAuthFlow(
                auth.authEndpoint(), auth.tokenEndpoint(), auth.clientId(), auth.redirectUri(), auth.scope());
        McpOAuthFlow.Pkce pkce = McpOAuthFlow.generatePkce();
        String state = java.util.UUID.randomUUID().toString();
        String url = flow.buildAuthUrl(pkce, state);
        System.out.println("opening browser to: " + url);
        BrowserLauncher.open(url);
        if (!BrowserLauncher.open(url)) {
            System.out.println("(could not auto-launch; please open the URL above manually)");
        }

        try {
            McpOAuthFlow.Token tok = orch.run(serverName, flow, pkce, 120, TimeUnit.SECONDS,
                    port -> {
                        try { return OAuthCallbackServer.startOnce(port, "/callback"); }
                        catch (java.io.IOException e) { throw new RuntimeException(e); }
                    });
            System.out.println("token acquired, expires at " + tok.expiresAtMs() + " (saved to " + tokens + ")");
            return 0;
        } catch (Exception e) {
            System.err.println("auth failed: " + e.getMessage());
            return 2;
        }
    }
}
