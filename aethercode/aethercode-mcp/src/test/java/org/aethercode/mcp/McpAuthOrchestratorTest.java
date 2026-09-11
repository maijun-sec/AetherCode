package org.aethercode.mcp;

import org.aethercode.mcp.auth.OAuthCallbackServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class McpAuthOrchestratorTest {

    @Test
    void loadsAuthBlock(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("mcp.json");
        Files.writeString(cfg, """
                {
                  "mcpServers": {
                    "remote-ide": {
                      "type": "sse",
                      "url": "https://example.com/mcp/sse",
                      "auth": {
                        "authEndpoint": "https://example.com/oauth/authorize",
                        "tokenEndpoint": "https://example.com/oauth/token",
                        "clientId": "aethercode-cli",
                        "scope": "mcp:read mcp:write"
                      }
                    }
                  }
                }
                """);
        McpAuthOrchestrator orch = new McpAuthOrchestrator(cfg, tmp.resolve("tokens.json"), 9876);
        McpAuthOrchestrator.ServerAuth a = orch.loadAuthConfig("remote-ide");
        assertThat(a).isNotNull();
        assertThat(a.authEndpoint()).isEqualTo("https://example.com/oauth/authorize");
        assertThat(a.tokenEndpoint()).isEqualTo("https://example.com/oauth/token");
        assertThat(a.clientId()).isEqualTo("aethercode-cli");
        assertThat(a.scope()).isEqualTo("mcp:read mcp:write");
        assertThat(a.redirectUri()).isEqualTo("http://127.0.0.1:9876/callback");
    }

    @Test
    void returnsNullForServerWithoutAuth(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("mcp.json");
        Files.writeString(cfg, """
                { "mcpServers": { "noauth": { "type": "stdio", "command": "noop" } } }
                """);
        McpAuthOrchestrator orch = new McpAuthOrchestrator(cfg, tmp.resolve("tokens.json"));
        assertThat(orch.loadAuthConfig("noauth")).isNull();
        assertThat(orch.loadAuthConfig("missing")).isNull();
    }

    @Test
    void honoursExplicitRedirectUri(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("mcp.json");
        Files.writeString(cfg, """
                { "mcpServers": {
                    "x": { "type": "sse", "url": "u",
                            "auth": { "authEndpoint": "a", "tokenEndpoint": "t",
                                      "clientId": "c", "redirectUri": "http://localhost:5555/cb" } } } }
                """);
        McpAuthOrchestrator orch = new McpAuthOrchestrator(cfg, tmp.resolve("tokens.json"));
        McpAuthOrchestrator.ServerAuth a = orch.loadAuthConfig("x");
        assertThat(a.redirectUri()).isEqualTo("http://localhost:5555/cb");
        assertThat(a.callbackPort()).isEqualTo(5555);
    }

    @Test
    void runExchangesAndPersists(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("mcp.json");
        Path tokens = tmp.resolve("tokens.json");
        McpAuthOrchestrator orch = new McpAuthOrchestrator(cfg, tokens, 0);

        AtomicInteger exchanged = new AtomicInteger();
        McpOAuthFlow flow = new McpOAuthFlow(
                "https://auth.example/authorize",
                "https://auth.example/token",
                "cid",
                "http://127.0.0.1:0/callback",
                "scope") {
            @Override
            public Token exchange(String code, Pkce pkce) {
                exchanged.incrementAndGet();
                return new Token("ACCESS-OK", "REFRESH-OK", System.currentTimeMillis() + 3600_000);
            }
        };
        McpOAuthFlow.Pkce pkce = McpOAuthFlow.generatePkce();

        // Server factory that completes the code after a short delay — uses OAuthCallbackServer.startOnce
        // but since OAuthCallbackServer's future is private, we run a real HTTP request to ourselves.
        Function<Integer, OAuthCallbackServer> factory = port -> {
            OAuthCallbackServer real;
            try {
                real = OAuthCallbackServer.startOnce(0, "/callback");
            } catch (Exception e) { throw new RuntimeException(e); }
            int actualPort = Integer.parseInt(real.url().split(":")[2].split("/")[0]);
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(50);
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL("http://127.0.0.1:" + actualPort + "/callback?code=CODE-123")
                                    .openConnection();
                    c.setRequestMethod("GET");
                    c.getResponseCode(); // fire it
                } catch (Exception ignored) {}
            });
            return real;
        };

        McpOAuthFlow.Token tok = orch.run("remote-ide", flow, pkce, 5, TimeUnit.SECONDS, factory);
        assertThat(tok.accessToken()).isEqualTo("ACCESS-OK");
        assertThat(tok.refreshToken()).isEqualTo("REFRESH-OK");
        assertThat(exchanged.get()).isEqualTo(1);
        assertThat(Files.exists(tokens)).isTrue();
        String body = Files.readString(tokens);
        assertThat(body).contains("ACCESS-OK").contains("remote-ide");
    }

    @Test
    void persistTokenOverwritesExisting(@TempDir Path tmp) throws Exception {
        Path tokens = tmp.resolve("tokens.json");
        McpAuthOrchestrator orch = new McpAuthOrchestrator(tmp.resolve("mcp.json"), tokens);
        orch.persistToken("a", new McpOAuthFlow.Token("AAA", null, 1));
        orch.persistToken("b", new McpOAuthFlow.Token("BBB", null, 2));
        String body = Files.readString(tokens);
        assertThat(body).contains("AAA").contains("BBB");
    }
}
