package org.aethercode.mcp;

import org.aethercode.mcp.McpHealthCheck.Registry;
import org.aethercode.mcp.McpHealthCheck.Status;
import org.aethercode.mcp.McpHealthDashboard.Dashboard;
import org.aethercode.mcp.McpHealthDashboard.ServerHealth;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHealthDashboardTest {

    @Test
    void build_emptyRegistry() {
        Registry r = new Registry();
        Dashboard d = new McpHealthDashboard().build(r);
        assertEquals(0, d.totalServers());
        assertEquals(0.0, d.overallUptime());
    }

    @Test
    void build_rejectsNullRegistry() {
        assertThrows(NullPointerException.class, () -> new McpHealthDashboard().build(null));
    }

    @Test
    void build_singleUpServer() {
        Registry r = new Registry();
        McpHealthCheck h = new McpHealthCheck("s1");
        h.probe(() -> {});
        r.register(h);
        Dashboard d = new McpHealthDashboard().build(r);
        assertEquals(1, d.totalServers());
        assertEquals(1, d.upCount());
        assertEquals(0, d.downCount());
    }

    @Test
    void build_singleDownServer() {
        Registry r = new Registry();
        McpHealthCheck h = new McpHealthCheck("s1", 1, java.time.Duration.ofSeconds(1));
        h.probe(() -> { throw new RuntimeException("x"); });
        r.register(h);
        Dashboard d = new McpHealthDashboard().build(r);
        assertEquals(1, d.downCount());
    }

    @Test
    void build_mixedStatuses() {
        Registry r = new Registry();
        McpHealthCheck up = new McpHealthCheck("up");
        up.probe(() -> {});
        McpHealthCheck down = new McpHealthCheck("down", 1, java.time.Duration.ofSeconds(1));
        down.probe(() -> { throw new RuntimeException("x"); });
        r.register(up);
        r.register(down);
        Dashboard d = new McpHealthDashboard().build(r);
        assertEquals(2, d.totalServers());
        assertEquals(1, d.upCount());
        assertEquals(1, d.downCount());
    }

    @Test
    void build_overallUptimeAggregates() {
        Registry r = new Registry();
        McpHealthCheck h1 = new McpHealthCheck("h1");
        h1.probe(() -> {}); // 1 success
        h1.probe(() -> { throw new RuntimeException("x"); }); // 1 failure
        McpHealthCheck h2 = new McpHealthCheck("h2");
        h2.probe(() -> {}); // 1 success
        r.register(h1);
        r.register(h2);
        Dashboard d = new McpHealthDashboard().build(r);
        // 2 successes, 1 failure = 0.6666
        assertEquals(0.6666, d.overallUptime(), 0.01);
    }

    @Test
    void build_healthScoreIsFractionUp() {
        Registry r = new Registry();
        McpHealthCheck up = new McpHealthCheck("up");
        up.probe(() -> {});
        McpHealthCheck down = new McpHealthCheck("down", 1, java.time.Duration.ofSeconds(1));
        down.probe(() -> { throw new RuntimeException("x"); });
        r.register(up);
        r.register(down);
        Dashboard d = new McpHealthDashboard().build(r);
        assertEquals(0.5, d.healthScore());
    }

    @Test
    void build_healthScoreZeroForEmpty() {
        Dashboard d = new McpHealthDashboard().build(new Registry());
        assertEquals(0.0, d.healthScore());
    }

    @Test
    void build_serverHealthIncludesUptime() {
        Registry r = new Registry();
        McpHealthCheck h = new McpHealthCheck("s1");
        h.probe(() -> {});
        h.probe(() -> { throw new RuntimeException("x"); });
        r.register(h);
        Dashboard d = new McpHealthDashboard().build(r);
        ServerHealth s = d.servers().get(0);
        assertEquals(0.5, s.uptimeRatio(), 0.01);
    }

    @Test
    void build_serverHealth_carriesLastError() {
        Registry r = new Registry();
        McpHealthCheck h = new McpHealthCheck("s1", 1, java.time.Duration.ofSeconds(1));
        h.probe(() -> { throw new RuntimeException("boom"); });
        r.register(h);
        Dashboard d = new McpHealthDashboard().build(r);
        assertEquals("boom", d.servers().get(0).lastError());
    }

    @Test
    void serverHealth_statusChecks() {
        ServerHealth up = new ServerHealth("s", Status.UP, 0, 0, 0, 1.0, null, null);
        ServerHealth deg = new ServerHealth("s", Status.DEGRADED, 0, 0, 0, 1.0, null, null);
        ServerHealth down = new ServerHealth("s", Status.DOWN, 0, 0, 0, 1.0, null, null);
        assertTrue(up.isHealthy());
        assertFalse(up.isDegraded());
        assertFalse(up.isDown());
        assertTrue(deg.isDegraded());
        assertTrue(down.isDown());
    }

    @Test
    void renderText_includesAllServers() {
        Registry r = new Registry();
        McpHealthCheck up = new McpHealthCheck("up");
        up.probe(() -> {});
        McpHealthCheck down = new McpHealthCheck("down", 1, java.time.Duration.ofSeconds(1));
        down.probe(() -> { throw new RuntimeException("x"); });
        r.register(up);
        r.register(down);
        Dashboard d = new McpHealthDashboard().build(r);
        String text = new McpHealthDashboard().renderText(d);
        assertTrue(text.contains("Health:"));
        assertTrue(text.contains("up"));
        assertTrue(text.contains("down"));
    }

    @Test
    void toMap_includesSummary() {
        Registry r = new Registry();
        McpHealthCheck up = new McpHealthCheck("up");
        up.probe(() -> {});
        r.register(up);
        Dashboard d = new McpHealthDashboard().build(r);
        var m = new McpHealthDashboard().toMap(d);
        assertNotNull(m.get("totalServers"));
        assertNotNull(m.get("up"));
        assertNotNull(m.get("healthScore"));
    }

    @Test
    void build_handlesUncheckedServers() {
        Registry r = new Registry();
        McpHealthCheck h = new McpHealthCheck("never-probed");
        r.register(h);
        Dashboard d = new McpHealthDashboard().build(r);
        assertEquals(1, d.unknownCount());
    }

    @Test
    void build_handlesDegradedStatus() {
        Registry r = new Registry();
        McpHealthCheck h = new McpHealthCheck("s1", 3, java.time.Duration.ofSeconds(1));
        h.probe(() -> { throw new RuntimeException("x"); });
        // 1 failure, threshold 3 → DEGRADED
        r.register(h);
        Dashboard d = new McpHealthDashboard().build(r);
        assertEquals(1, d.degradedCount());
    }
}
