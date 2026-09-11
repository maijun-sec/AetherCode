package org.aethercode.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aethercode.mcp.McpHealthCheck.Registry;
import org.aethercode.mcp.McpHealthCheck.Snapshot;
import org.aethercode.mcp.McpHealthCheck.Status;

/**
 * aggregates {@link McpHealthCheck} data into a single
 * dashboard-friendly snapshot. Used by the {@code /health} TUI
 * command and the {@code /doctor} introspection flow.
 */
public class McpHealthDashboard {

    public record ServerHealth(
            String id,
            Status status,
            long consecutiveFailures,
            long totalProbes,
            long totalFailures,
            double uptimeRatio,
            String lastError,
            Instant lastChecked
    ) {
        public boolean isHealthy() { return status == Status.UP; }
        public boolean isDegraded() { return status == Status.DEGRADED; }
        public boolean isDown() { return status == Status.DOWN; }
    }

    public record Dashboard(
            int totalServers,
            int upCount,
            int downCount,
            int degradedCount,
            int unknownCount,
            long totalProbes,
            long totalFailures,
            double overallUptime,
            Duration totalCheckWindow,
            List<ServerHealth> servers
    ) {
        public double healthScore() {
            if (totalServers == 0) return 0.0;
            return (double) upCount / (double) totalServers;
        }
    }

    public McpHealthDashboard() {}

    public Dashboard build(Registry registry) {
        Objects.requireNonNull(registry, "registry");
        var snapshots = registry.snapshots();
        int total = snapshots.size();
        int up = registry.upCount();
        int down = registry.downCount();
        long totalProbes = 0;
        long totalFailures = 0;
        java.util.List<ServerHealth> servers = new java.util.ArrayList<>();
        Instant earliest = null;
        Instant latest = null;
        for (Snapshot s : snapshots.values()) {
            int degraded = countByStatus(snapshots, Status.DEGRADED);
            int unknown = countByStatus(snapshots, Status.UNKNOWN);
            totalProbes += s.totalProbes();
            totalFailures += s.totalFailures();
            if (s.lastChecked() != null) {
                if (earliest == null || s.lastChecked().isBefore(earliest)) earliest = s.lastChecked();
                if (latest == null || s.lastChecked().isAfter(latest)) latest = s.lastChecked();
            }
            McpHealthCheck h = registry.get(s.serverId());
            double uptime = h == null ? 0.0 : h.uptimeRatio();
            servers.add(new ServerHealth(
                    s.serverId(), s.status(), s.consecutiveFailures(),
                    s.totalProbes(), s.totalFailures(),
                    uptime, s.lastError(), s.lastChecked()));
        }
        Duration window = (earliest == null || latest == null) ? Duration.ZERO
                : Duration.between(earliest, latest);
        double overall = totalProbes == 0 ? 0.0 : 1.0 - (double) totalFailures / (double) totalProbes;
        return new Dashboard(total, up, down,
                countByStatus(snapshots, Status.DEGRADED),
                countByStatus(snapshots, Status.UNKNOWN),
                totalProbes, totalFailures, overall, window, servers);
    }

    private static int countByStatus(Map<String, Snapshot> snaps, Status s) {
        int n = 0;
        for (Snapshot v : snaps.values()) if (v.status() == s) n++;
        return n;
    }

    /** a text rendering of the dashboard for the TUI. */
    public String renderText(Dashboard d) {
        StringBuilder sb = new StringBuilder();
        sb.append("Health: ").append(d.upCount()).append(" up, ")
          .append(d.degradedCount()).append(" degraded, ")
          .append(d.downCount()).append(" down, ")
          .append(d.unknownCount()).append(" unknown\n");
        sb.append("Overall uptime: ").append(String.format("%.2f%%", d.overallUptime() * 100)).append("\n");
        for (ServerHealth s : d.servers()) {
            sb.append("  ").append(s.id()).append(": ").append(s.status())
              .append(" (").append(s.consecutiveFailures()).append(" consecutive failures)\n");
        }
        return sb.toString();
    }

    /** a JSON-friendly map view. */
    public Map<String, Object> toMap(Dashboard d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalServers", d.totalServers());
        out.put("up", d.upCount());
        out.put("down", d.downCount());
        out.put("degraded", d.degradedCount());
        out.put("unknown", d.unknownCount());
        out.put("totalProbes", d.totalProbes());
        out.put("totalFailures", d.totalFailures());
        out.put("overallUptime", d.overallUptime());
        out.put("healthScore", d.healthScore());
        return out;
    }
}
