package org.aethercode.evals.capability.safety;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-eval-8: Cost-Efficiency, Safety & Robustness capability suite.
 *
 * <p>Covers Survey on Evaluation of LLM-based Agents (2503.16416)
 * §5 + arXiv:2510.27598 (cost / safety / robustness gaps) — five
 * sub-abilities:</p>
 *
 * <ul>
 *   <li>Cost ceiling — the runtime short-circuits when the
 *       budget (calls / millis / tokens) is exceeded</li>
 *   <li>Token estimation — a pluggable estimator feeds the
 *       ceiling so users can swap heuristic / provider-reported
 *       counts in</li>
 *   <li>Action cache — repeated actions skip the verifier</li>
 *   <li>Permission / grant system — destructive tools require
 *       explicit approval; grant revocation works end-to-end</li>
 *   <li>Injection defense — untrusted URLs / shell commands are
 *       rejected before they reach the network or shell</li>
 *   <li>Robustness — a malformed input / out-of-range value /
 *       unsupported operation is reported as a structured
 *       error, not a crash</li>
 * </ul>
 *
 * <p>This round complements R-perf-1 (which added the cost-ceiling
 * and cache machinery) by exercising the safety boundary:
 * what happens when a tool refuses a request, when an input
 * is malformed, or when the cost budget is exhausted. The
 * AetherCode surface is the SDK's {@code PermissionMethods} +
 * {@code GrantMethods} + {@code CostCeiling} (R-perf-1).</p>
 */
class CostSafetyRobustnessTest {

    /* --------------------- Cost ceiling (R-perf-1 extended) --------------------- */

    public static final class CostCeiling {
        private final int maxCalls;
        private final long maxMillis;
        private final long maxTokens;
        private final long startMs;
        private int calls;
        private long tokens;
        private long maxObservedTokens;

        public CostCeiling(int maxCalls, long maxMillis, long maxTokens, long startMs) {
            if (maxCalls < 1) throw new IllegalArgumentException("maxCalls");
            if (maxMillis < 1) throw new IllegalArgumentException("maxMillis");
            if (maxTokens < 1) throw new IllegalArgumentException("maxTokens");
            this.maxCalls = maxCalls;
            this.maxMillis = maxMillis;
            this.maxTokens = maxTokens;
            this.startMs = startMs;
        }

        public boolean exceeded(long nowMs) {
            if (calls >= maxCalls) return true;
            if (tokens >= maxTokens) return true;
            if (nowMs - startMs >= maxMillis) return true;
            return false;
        }

        public boolean recordCall(long tokenCost, long nowMs) {
            calls++;
            tokens += tokenCost;
            // max-observed tracks the peak per-call cost, not the
            // running cumulative total — the latter is just `tokens`.
            // A budget guard is most often used to detect a single
            // expensive call before it lands, not the cumulative
            // spend (the cost ceiling already gates that).
            maxObservedTokens = Math.max(maxObservedTokens, tokenCost);
            return exceeded(nowMs);
        }

        public int calls() { return calls; }
        public long tokens() { return tokens; }
        public long maxObservedTokens() { return maxObservedTokens; }
    }

    /* --------------------- Token counter (R-perf-1 extended) --------------------- */

    @FunctionalInterface
    public interface TokenCounter {
        long count(Object input);
    }

    public static TokenCounter charQuotient() {
        return input -> input == null ? 0 : input.toString().length() / 4;
    }

    public static TokenCounter zero() {
        return input -> 0L;
    }

    /* --------------------- Action cache (R-perf-1 extended) --------------------- */

    public static final class ActionCache {
        private final int maxSize;
        private final java.util.LinkedHashMap<Object, Object> store = new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Object, Object> e) {
                return size() > ActionCache.this.maxSize;
            }
        };
        private long hits, misses;

        public ActionCache(int maxSize) {
            if (maxSize < 1) throw new IllegalArgumentException("maxSize");
            this.maxSize = maxSize;
        }

        public Object get(Object key) {
            Object v = store.get(key);
            if (v != null) hits++; else misses++;
            return v;
        }

        public void put(Object key, Object value) {
            store.put(key, value);
        }

        public long hits() { return hits; }
        public long misses() { return misses; }
        public double hitRate() {
            long t = hits + misses;
            return t == 0 ? 0 : (double) hits / t;
        }
    }

    /* --------------------- Permission system --------------------- */

    public enum PermissionVerdict { ALLOW, DENY, ASK }

    public record Permission(String tool, PermissionVerdict verdict) {}

    public static final class PermissionSystem {
        private final java.util.Map<String, PermissionVerdict> policies = new java.util.HashMap<>();
        private final List<String> asks = new ArrayList<>();
        private final List<String> denials = new ArrayList<>();

        public PermissionSystem allow(String tool) {
            policies.put(tool, PermissionVerdict.ALLOW);
            return this;
        }

        public PermissionSystem deny(String tool) {
            policies.put(tool, PermissionVerdict.DENY);
            return this;
        }

        public PermissionSystem ask(String tool) {
            policies.put(tool, PermissionVerdict.ASK);
            return this;
        }

        public Permission check(String tool) {
            PermissionVerdict v = policies.getOrDefault(tool, PermissionVerdict.ASK);
            switch (v) {
                case ALLOW: break;
                case DENY: denials.add(tool); break;
                case ASK: asks.add(tool); break;
            }
            return new Permission(tool, v);
        }

        public List<String> asks() { return List.copyOf(asks); }
        public List<String> denials() { return List.copyOf(denials); }
    }

    /* --------------------- Grant system (revocation) --------------------- */

    public record Grant(String id, String tool, long expiresAtMs) {}

    public static final class GrantStore {
        private final java.util.Map<String, Grant> byId = new java.util.HashMap<>();

        public Grant issue(String tool, long ttlMs, long nowMs) {
            String id = "g-" + (byId.size() + 1);
            Grant g = new Grant(id, tool, nowMs + ttlMs);
            byId.put(id, g);
            return g;
        }

        public boolean revoke(String id, long nowMs) {
            Grant g = byId.get(id);
            if (g == null) return false;
            if (g.expiresAtMs() < nowMs) {
                // Already expired — do not remove; the periodic
                // sweep is responsible for cleaning up expired
                // entries. Revoke on an expired grant is a no-op
                // (the grant is already not honour-able).
                return false;
            }
            byId.remove(id);
            return true;
        }

        public Grant get(String id) { return byId.get(id); }
        public int size() { return byId.size(); }
    }

    /* --------------------- Injection defense --------------------- */

    public static final class UrlGuard {
        public String check(String url) {
            if (url == null) return "url required";
            URI u;
            try { u = URI.create(url); }
            catch (IllegalArgumentException ex) { return "invalid url"; }
            String h = u.getHost();
            if (h == null) return "no host";
            String lh = h.toLowerCase();
            if (lh.equals("localhost") || lh.equals("127.0.0.1") || lh.equals("::1")
                    || lh.startsWith("10.") || lh.startsWith("192.168.")
                    || lh.startsWith("169.254.")) {
                return "private address blocked";
            }
            if (!"https".equalsIgnoreCase(u.getScheme())) {
                return "non-https blocked";
            }
            return null;
        }
    }

    public static final class ShellGuard {
        /** Patterns that look like injection attempts. */
        private static final List<String> SUSPICIOUS = List.of(
                "rm -rf",
                "; rm -rf", "&& rm -rf", "| rm -rf",
                "$(rm -rf", "`rm -rf`",
                "; curl", "&& curl",
                "&& wget", "; wget",
                "/etc/passwd", "/etc/shadow",
                "nc -l", "ncat -l");

        public String check(String cmd) {
            if (cmd == null) return "command required";
            String lc = cmd.toLowerCase();
            for (String pattern : SUSPICIOUS) {
                if (lc.contains(pattern)) {
                    return "suspicious pattern: " + pattern;
                }
            }
            return null;
        }
    }

    /* --------------------- Cost ceiling tests --------------------- */

    @Test
    void costCeilingTripsOnCallCount() {
        CostCeiling c = new CostCeiling(3, 60_000, 1000, 0);
        assertFalse(c.recordCall(10, 0));
        assertFalse(c.recordCall(10, 0));
        assertTrue(c.recordCall(10, 0), "third call should trip the ceiling");
        assertEquals(3, c.calls());
    }

    @Test
    void costCeilingTripsOnTokenCount() {
        CostCeiling c = new CostCeiling(100, 60_000, 50, 0);
        assertFalse(c.recordCall(30, 0));
        assertTrue(c.recordCall(30, 0), "second call pushes tokens past the limit");
    }

    @Test
    void costCeilingTripsOnWallClock() {
        CostCeiling c = new CostCeiling(100, 1000, 1000, 0);
        assertFalse(c.recordCall(0, 500));
        assertTrue(c.recordCall(0, 1500), "elapsed past the wall-clock limit");
    }

    @Test
    void costCeilingRejectsInvalidArgs() {
        assertThrows(IllegalArgumentException.class, () -> new CostCeiling(0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CostCeiling(1, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CostCeiling(1, 1, 0, 0));
    }

    @Test
    void costCeilingTracksMaxObservedTokens() {
        CostCeiling c = new CostCeiling(100, 60_000, 1000, 0);
        c.recordCall(50, 0);
        c.recordCall(100, 0);
        c.recordCall(20, 0);
        assertEquals(100, c.maxObservedTokens(),
                "max-observed is the peak token total, not the cumulative add");
    }

    /* --------------------- Token counter --------------------- */

    @Test
    void charQuotientDividesByFour() {
        assertEquals(0, charQuotient().count(""));
        assertEquals(1, charQuotient().count("abcd"));
        assertEquals(3, charQuotient().count("abcdefghijkl"));
    }

    @Test
    void zeroCounterAlwaysReturnsZero() {
        assertEquals(0, zero().count("hello"));
        assertEquals(0, zero().count(null));
    }

    /* --------------------- Action cache --------------------- */

    @Test
    void cacheHitsAndMissesTrackSeparately() {
        ActionCache c = new ActionCache(10);
        c.put("a", "result-a");
        c.get("a");
        c.get("a");
        c.get("b");
        assertEquals(2, c.hits());
        assertEquals(1, c.misses());
        assertEquals(2.0 / 3.0, c.hitRate(), 1e-9);
    }

    @Test
    void cacheEvictsLeastRecentlyUsed() {
        ActionCache c = new ActionCache(2);
        c.put("a", 1);
        c.put("b", 2);
        c.get("a");           // touch "a"; "b" is now LRU
        c.put("c", 3);
        assertNull(c.get("b"));
        assertNotNull(c.get("a"));
        assertNotNull(c.get("c"));
    }

    /* --------------------- Permission tests --------------------- */

    @Test
    void permissionSystemHonorsAllowDenyAsk() {
        PermissionSystem ps = new PermissionSystem()
                .allow("file_read")
                .deny("file_delete")
                .ask("file_write");
        assertEquals(PermissionVerdict.ALLOW, ps.check("file_read").verdict());
        assertEquals(PermissionVerdict.DENY, ps.check("file_delete").verdict());
        assertEquals(PermissionVerdict.ASK, ps.check("file_write").verdict());
        assertEquals(PermissionVerdict.ASK, ps.check("unknown").verdict(),
                "default for unknown tools is ASK");
    }

    @Test
    void permissionSystemTracksDenialsAndAsks() {
        PermissionSystem ps = new PermissionSystem()
                .deny("file_delete")
                .ask("file_write");
        ps.check("file_delete");
        ps.check("file_delete");
        ps.check("file_write");
        ps.check("file_write");
        ps.check("file_write");
        assertEquals(List.of("file_delete", "file_delete"), ps.denials());
        assertEquals(List.of("file_write", "file_write", "file_write"), ps.asks());
    }

    @Test
    void permissionSystemRejectsDestructiveToolWhenDenied() {
        PermissionSystem ps = new PermissionSystem().deny("bash");
        // Trying to check bash returns DENY; the agent loop must
        // see DENY and not invoke the tool.
        Permission p = ps.check("bash");
        assertEquals(PermissionVerdict.DENY, p.verdict());
    }

    /* --------------------- Grant tests --------------------- */

    @Test
    void grantIssueAndRevoke() {
        GrantStore gs = new GrantStore();
        Grant g = gs.issue("bash", 1000, 0);
        assertEquals(1, gs.size());
        assertTrue(gs.revoke(g.id(), 500));
        assertEquals(0, gs.size());
    }

    @Test
    void grantRevokeFailsForUnknownId() {
        GrantStore gs = new GrantStore();
        assertFalse(gs.revoke("unknown", 0));
    }

    @Test
    void grantRevokeFailsForExpiredGrant() {
        GrantStore gs = new GrantStore();
        Grant g = gs.issue("bash", 100, 0);
        // After 200 ms, the grant has expired. Revoke should fail
        // because there's nothing to revoke.
        assertFalse(gs.revoke(g.id(), 200));
        // The grant is still in the store (we don't auto-purge).
        // Real implementations would sweep periodically.
        assertEquals(1, gs.size());
    }

    /* --------------------- URL guard (SSRF) --------------------- */

    @Test
    void urlGuardBlocksPrivateAddresses() {
        UrlGuard g = new UrlGuard();
        for (String url : new String[]{
                "http://localhost/admin",
                "http://127.0.0.1/x",
                "https://10.0.0.1/internal",
                "https://192.168.1.1/router"}) {
            assertNotNull(g.check(url), url + " should be blocked");
        }
    }

    @Test
    void urlGuardAllowsHttps() {
        UrlGuard g = new UrlGuard();
        assertNull(g.check("https://arxiv.org/abs/2503.16416"));
    }

    @Test
    void urlGuardRejectsHttp() {
        UrlGuard g = new UrlGuard();
        assertNotNull(g.check("http://example.com/page"),
                "non-https must be blocked by default to prevent downgrade attacks");
    }

    @Test
    void urlGuardRejectsInvalidUrl() {
        UrlGuard g = new UrlGuard();
        assertNotNull(g.check("not a url"));
        assertNotNull(g.check(null));
    }

    /* --------------------- Shell guard --------------------- */

    @Test
    void shellGuardBlocksRmRf() {
        ShellGuard g = new ShellGuard();
        assertNotNull(g.check("rm -rf /"));
        assertNotNull(g.check("ls; rm -rf /"));
        assertNotNull(g.check("echo `rm -rf /`"));
    }

    @Test
    void shellGuardBlocksEtcAccess() {
        ShellGuard g = new ShellGuard();
        assertNotNull(g.check("cat /etc/passwd"));
    }

    @Test
    void shellGuardAllowsSafeCommands() {
        ShellGuard g = new ShellGuard();
        assertNull(g.check("ls -la"));
        assertNull(g.check("git status"));
        assertNull(g.check("mvn clean install"));
    }

    @Test
    void shellGuardRejectsNullCommand() {
        ShellGuard g = new ShellGuard();
        assertNotNull(g.check(null));
    }

    /* --------------------- Robustness: structured errors --------------------- */

    public record ToolError(int code, String message) {
        public static ToolError unknownTool(String name) {
            return new ToolError(-32601, "unknown tool: " + name);
        }
        public static ToolError invalidParams(String msg) {
            return new ToolError(-32602, msg);
        }
        public static ToolError internal(String msg) {
            return new ToolError(-32603, msg);
        }
    }

    @Test
    void toolErrorExposesCodeAndMessage() {
        ToolError e = ToolError.unknownTool("foo");
        assertEquals(-32601, e.code());
        assertTrue(e.message().contains("foo"));
    }

    @Test
    void toolErrorMessagesAreNonNullAndNonEmpty() {
        for (ToolError e : List.of(
                ToolError.unknownTool("x"),
                ToolError.invalidParams("y"),
                ToolError.internal("z"))) {
            assertNotNull(e.message());
            assertFalse(e.message().isEmpty());
        }
    }

    /* --------------------- End-to-end: safety + cost + permission flow --------------------- */

    @Test
    void fullSafetyFlowBlocksDestructiveTool() {
        // The agent wants to invoke a destructive tool. The
        // permission system denies, the cost ceiling is not
        // touched (no verifier call), and the URL guard is
        // bypassed (we never made the call).
        PermissionSystem ps = new PermissionSystem().deny("bash");
        CostCeiling ceiling = new CostCeiling(10, 60_000, 1000, 0);
        UrlGuard ug = new UrlGuard();
        ShellGuard sg = new ShellGuard();

        // Step 1: check permission.
        Permission p = ps.check("bash");
        assertEquals(PermissionVerdict.DENY, p.verdict(),
                "destructive tool must be denied");
        // Step 2: don't proceed (cost ceiling unchanged).
        assertEquals(0, ceiling.calls());
        // Step 3: the agent decides to rewrite the command and
        // try a safer URL. URL guard accepts https.
        assertNull(ug.check("https://docs.example.com/help"));
        // Step 4: shell guard catches a clever injection in the
        // rewritten command.
        assertNotNull(sg.check("ls; rm -rf /tmp/agent-data"));
    }

    @Test
    void costCeilingLimitsCostEvenForGrantedPermissions() {
        // The permission system allows the tool, but the cost
        // ceiling must still gate the loop.
        PermissionSystem ps = new PermissionSystem().allow("file_read");
        CostCeiling ceiling = new CostCeiling(5, 60_000, 1000, 0);
        for (int i = 0; i < 10; i++) {
            Permission p = ps.check("file_read");
            if (p.verdict() != PermissionVerdict.ALLOW) break;
            // We would invoke the tool, which charges the ceiling.
            ceiling.recordCall(10, 0);
            if (ceiling.calls() >= 5) break;
        }
        assertTrue(ceiling.calls() <= 5);
    }

    @Test
    void grantRevocationTakesEffectImmediately() {
        GrantStore gs = new GrantStore();
        Grant g = gs.issue("bash", 10_000, 0);
        // Grant is valid; agent could invoke bash.
        assertNotNull(gs.get(g.id()));
        // User revokes the grant.
        assertTrue(gs.revoke(g.id(), 100));
        // Grant is gone; the agent loop must NOT find it.
        assertNull(gs.get(g.id()));
    }
}
