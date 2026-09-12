package org.aethercode.evals.sdk.mcp;

import org.aethercode.mcp.AuthRateLimiter;
import org.aethercode.mcp.McpHealthCheck;
import org.aethercode.mcp.McpHealthCheck.Probe;
import org.aethercode.mcp.McpHealthCheck.Snapshot;
import org.aethercode.mcp.McpHealthCheck.Status;
import org.aethercode.mcp.McpRegistry;
import org.aethercode.mcp.McpRegistry.Entry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-sdk-12: AetherCode MCP Interface conformance.
 *
 * <p>The {@code aethercode-mcp} module is the engine's gateway to
 * the Model Context Protocol. The TUI's "MCP servers" panel reads
 * {@link McpRegistry#list()} to render the catalog; the OAuth
 * callback flow uses {@link AuthRateLimiter} to throttle repeated
 * auth attempts; the supervisor pings servers via
 * {@link McpHealthCheck}. R-AUDIT-SELF-IMPROVEMENT flagged this as
 * Tier-2 because a silent catalog drift shows up as "MCP server
 * missing" with no trace.</p>
 */
class SdkMcpInterfaceTest {

    /* ---------------- McpRegistry catalog ---------------- */

    @Test
    void registryContainsCoreServers() {
        List<String> names = McpRegistry.names();
        // The four canonical servers must be present; the front-end
        // lists these by name.
        assertTrue(names.contains("filesystem"));
        assertTrue(names.contains("git"));
        assertTrue(names.contains("fetch"));
        assertTrue(names.contains("sqlite"));
    }

    @Test
    void registryGetReturnsNullForUnknown() {
        assertNull(McpRegistry.get("nope-not-a-real-server"));
    }

    @Test
    void registryGetReturnsEntryForKnown() {
        Entry e = McpRegistry.get("filesystem");
        assertNotNull(e);
        assertEquals("filesystem", e.name());
        assertNotNull(e.description());
    }

    @Test
    void registryListIsUnmodifiable() {
        List<Entry> list = McpRegistry.list();
        assertThrows(UnsupportedOperationException.class, () -> list.add(null));
    }

    @Test
    void registryEntryToConfigStdio() {
        Entry e = McpRegistry.get("filesystem");
        Map<String, Object> cfg = e.toConfig();
        assertEquals("stdio", cfg.get("type"));
        assertEquals("npx", cfg.get("command"));
        assertNotNull(cfg.get("args"));
    }

    @Test
    void registryEntryToConfigSse() {
        Entry e = McpRegistry.get("remote-fetch");
        Map<String, Object> cfg = e.toConfig();
        assertEquals("sse", cfg.get("type"));
        assertNotNull(cfg.get("url"));
    }

    @Test
    void registryToMcpJsonWrapsInMcpServers() {
        Map<String, Object> json = McpRegistry.toMcpJson("filesystem", null);
        assertTrue(json.containsKey("mcpServers"));
        Object inner = json.get("mcpServers");
        assertTrue(inner instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> innerMap = (Map<String, Object>) inner;
        assertTrue(innerMap.containsKey("filesystem"));
    }

    @Test
    void registryToMcpJsonMergesExtras() {
        // Caller overrides the command for a local binary.
        Map<String, Object> extras = Map.of("command", "/usr/local/bin/npx");
        Map<String, Object> json = McpRegistry.toMcpJson("filesystem", extras);
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) json.get("mcpServers");
        @SuppressWarnings("unchecked")
        Map<String, Object> fs = (Map<String, Object>) inner.get("filesystem");
        assertEquals("/usr/local/bin/npx", fs.get("command"),
                "extras should override the catalog default");
    }

    @Test
    void registryToMcpJsonThrowsForUnknown() {
        assertThrows(IllegalArgumentException.class,
                () -> McpRegistry.toMcpJson("not-a-real-server", null));
    }

    /* ---------------- McpHealthCheck: status enum + construction ---------------- */

    @Test
    void healthCheckStatusEnumHasFourValues() {
        assertEquals(4, Status.values().length,
                "McpHealthCheck.Status taxonomy drifted — review dashboard");
    }

    @Test
    void healthCheckStartsUnknown() {
        McpHealthCheck h = new McpHealthCheck("test");
        assertEquals(Status.UNKNOWN, h.status());
        assertEquals(0, h.snapshot().consecutiveFailures());
        assertEquals(0, h.snapshot().totalProbes());
    }

    @Test
    void healthCheckRejectsBlankServerId() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpHealthCheck(""));
        assertThrows(IllegalArgumentException.class,
                () -> new McpHealthCheck(null));
    }

    @Test
    void healthCheckRejectsBadThreshold() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpHealthCheck("t", 0, Duration.ofSeconds(1)));
    }

    @Test
    void healthCheckRejectsZeroTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpHealthCheck("t", 3, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new McpHealthCheck("t", 3, Duration.ofSeconds(-1)));
    }

    @Test
    void healthCheckSuccessesMarkUp() throws Exception {
        McpHealthCheck h = new McpHealthCheck("test");
        h.probe(() -> { /* no-op success */ });
        assertEquals(Status.UP, h.status());
        Snapshot snap = h.snapshot();
        assertEquals(1, snap.totalProbes());
        assertEquals(0, snap.totalFailures());
        assertNotNull(snap.lastSuccess());
    }

    @Test
    void healthCheckFailuresEscalateToDown() throws Exception {
        McpHealthCheck h = new McpHealthCheck("test", 2, Duration.ofSeconds(1));
        Probe failing = () -> { throw new RuntimeException("network down"); };
        // 1st failure: still DEGRADED (below threshold of 2).
        h.probe(failing);
        assertEquals(Status.DEGRADED, h.status());
        // 2nd failure: now DOWN.
        h.probe(failing);
        assertEquals(Status.DOWN, h.status());
        assertEquals(2, h.snapshot().consecutiveFailures());
    }

    @Test
    void healthCheckRecoverDownToUp() throws Exception {
        McpHealthCheck h = new McpHealthCheck("test", 1, Duration.ofSeconds(1));
        h.probe(() -> { throw new RuntimeException("down"); });
        assertEquals(Status.DOWN, h.status());
        // One success returns the server to UP.
        h.probe(() -> {});
        assertEquals(Status.UP, h.status());
    }

    @Test
    void healthCheckAttributesRoundTrip() {
        McpHealthCheck h = new McpHealthCheck("test");
        h.setAttribute("region", "us-east");
        assertEquals("us-east", h.getAttribute("region"));
        assertNull(h.getAttribute("missing"));
    }

    /* ---------------- AuthRateLimiter ---------------- */

    @Test
    void rateLimiterFirstAcquireSucceeds() {
        AuthRateLimiter rl = new AuthRateLimiter(60_000L);
        assertTrue(rl.tryAcquire("server-1"));
    }

    @Test
    void rateLimiterBlocksWithinCooldown() {
        AuthRateLimiter rl = new AuthRateLimiter(60_000L);
        assertTrue(rl.tryAcquire("server-1"));
        assertFalse(rl.tryAcquire("server-1"),
                "second attempt within cooldown must be blocked");
    }

    @Test
    void rateLimiterIsPerServer() {
        AuthRateLimiter rl = new AuthRateLimiter(60_000L);
        assertTrue(rl.tryAcquire("server-1"));
        // server-2 has its own cooldown.
        assertTrue(rl.tryAcquire("server-2"));
    }

    @Test
    void rateLimiterClearResets() {
        AuthRateLimiter rl = new AuthRateLimiter(60_000L);
        rl.tryAcquire("server-1");
        rl.clear("server-1");
        // After clear, server-1 should be able to acquire again.
        assertTrue(rl.tryAcquire("server-1"));
    }

    @Test
    void rateLimiterRemainingMsReflectsCooldown() {
        AuthRateLimiter rl = new AuthRateLimiter(60_000L);
        rl.tryAcquire("server-1");
        long remaining = rl.remainingMs("server-1");
        assertTrue(remaining > 0 && remaining <= 60_000L,
                "remaining should be in (0, 60_000], got " + remaining);
    }

    @Test
    void rateLimiterUnknownServerReturnsZero() {
        AuthRateLimiter rl = new AuthRateLimiter();
        assertEquals(0L, rl.remainingMs("never-seen"));
    }
}
