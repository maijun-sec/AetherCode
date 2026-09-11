package org.aethercode.protocol.methods;

import org.aethercode.core.tool.Tool;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.sdk.SessionStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contract test for the {@code engineHealth} RPC and the
 * engine-level reliability hooks ({@code recordPing},
 * {@code lastPingAtMs}, {@code recordError},
 * {@code SessionStats.lastErrorAtMs}).
 *
 * <p>The user explicitly asked for "complete optimization, the backend still based on
 * Java; the Java backend still has much room to optimize, e.g. poor reliability, frequent disconnections".
 * The R164 surface gives the TUI a single, structured probe to
 * answer "is the daemon alive, idle, errored, or stuck?":
 * <ol>
 *   <li>{@code engineHealth} returns
 *       {@code {ok, healthy, model, sessionId, permissionMode,
 *       state, lastError, lastErrorAtMs, lastErrorForMs,
 *       lastActivityAtMs, idleForMs, lastPingAtMs,
 *       lastPingForMs, queries, totalToolCalls,
 *       pendingPermissionCount, ...}}.</li>
 *   <li>{@code ping} still works (prior round surface) AND now also
 *       records a heartbeat in the engine so the TUI can
 *       detect a frozen client.</li>
 *   <li>{@code SessionStats.recordError(Throwable)} captures
 *       the message + stamps lastErrorAtMs for the health
 *       snapshot.</li>
 * </ol>
 */
class AetherCodeMethodsR164Test {

    private static AetherCodeEngine engineFor(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    private static AetherCodeMethods methodsWith(Path cwd, List<JsonRpcNotification> out) {
        return new AetherCodeMethods(engineFor(cwd), n -> out.add(n));
    }

    @Test
    void engineHealth_returnsShapeAndHealthyByDefault(@TempDir Path cwd) {
        AetherCodeEngine eng = engineFor(cwd);
        AetherCodeMethods m = new AetherCodeMethods(eng, n -> {});
        @SuppressWarnings("unchecked")
        Map<String, Object> h = (Map<String, Object>) m.engineHealth(Map.of());
        assertThat(h.get("ok")).isEqualTo(true);
        assertThat(h.get("healthy")).isEqualTo(true);
        // version is null when running from the IDE classpath
        // (no jar manifest). When packaged in a release jar
        // the Implementation-Version attribute surfaces here.
        assertThat(h).containsKey("version");
        assertThat(h.get("uptimeMs")).isInstanceOf(Long.class);
        assertThat(h.get("model")).isEqualTo("MiniMax-M3");
        assertThat(h.get("sessionId")).isNotNull();
        assertThat(h.get("permissionMode")).isInstanceOf(String.class);
        assertThat(h.get("state")).isEqualTo("running");
        assertThat(h.get("lastError")).isEqualTo("");
        assertThat(h.get("lastErrorAtMs")).isEqualTo(0L);
        assertThat(h.get("lastErrorForMs")).isEqualTo(-1L);
        // engineHealth itself records a heartbeat, so
        // lastPingAtMs is now non-zero and lastPingForMs is
        // a few ms. The semantic "did a client reach me"
        // is what we want to surface.
        long lastPingAtMs = (Long) h.get("lastPingAtMs");
        assertThat(lastPingAtMs).isGreaterThan(0L);
        long lastPingForMs = (Long) h.get("lastPingForMs");
        assertThat(lastPingForMs).isBetween(0L, 5_000L);
        // idleForMs is "now - lastActivityAtMs". The
        // SessionStats constructor stamps lastActivityAtMs
        // to "now" so a fresh engine has an idle of a few
        // ms (depending on how long the engine build took).
        long idleForMs = (Long) h.get("idleForMs");
        assertThat(idleForMs).isBetween(0L, 5_000L);
        assertThat(h.get("pendingPermissionCount")).isEqualTo(0);
    }

    @Test
    void ping_recordsHeartbeatInEngine(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        AetherCodeEngine eng = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        // Build a methods pointing at the same engine so we
        // can verify the side-effect of ping on the engine.
        AetherCodeMethods m2 = new AetherCodeMethods(eng, n -> {});
        assertThat(eng.lastPingAtMs()).isEqualTo(0L);
        long before = System.currentTimeMillis();
        m2.ping(Map.of());
        long after = System.currentTimeMillis();
        long pingedAt = eng.lastPingAtMs();
        assertThat(pingedAt).isBetween(before, after);
    }

    @Test
    void engineHealth_includesLastPingAfterPing(@TempDir Path cwd) {
        AetherCodeEngine eng = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        AetherCodeMethods m = new AetherCodeMethods(eng, n -> {});
        m.ping(Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> h = (Map<String, Object>) m.engineHealth(Map.of());
        // After ping, lastPingAtMs is the wall-clock millis
        // of the just-completed ping; lastPingForMs is
        // small (a few ms, depending on test machine speed).
        long pingAt = (Long) h.get("lastPingAtMs");
        long pingFor = (Long) h.get("lastPingForMs");
        assertThat(pingAt).isGreaterThan(0L);
        // engineHealth also bumps lastPingAtMs, so pingFor
        // is at most a few hundred ms.
        assertThat(pingFor).isBetween(0L, 5_000L);
    }

    @Test
    void sessionStats_recordError_capturesMessageAndStampsAt(@TempDir Path cwd) {
        AetherCodeEngine eng = engineFor(cwd);
        SessionStats stats = eng.sessionStats();
        assertThat(stats.lastErrorAtMs()).isEqualTo(0L);
        long before = System.currentTimeMillis();
        stats.recordError(new IllegalStateException("test boom"));
        long after = System.currentTimeMillis();
        assertThat(stats.lastError()).isEqualTo("test boom");
        assertThat(stats.lastErrorAtMs()).isBetween(before, after);
        // recordError also bumps lastActivityAtMs.
        assertThat(stats.lastActivityAtMs()).isBetween(before, after);
    }

    @Test
    void sessionStats_recordError_handlesNullMessageAndCapsLength(@TempDir Path cwd) {
        AetherCodeEngine eng = engineFor(cwd);
        SessionStats stats = eng.sessionStats();
        // Null message falls back to the class name.
        stats.recordError(new RuntimeException((String) null));
        assertThat(stats.lastError()).isEqualTo("RuntimeException");
        // Very long messages are capped to ~2 KB (substring 2044
        // chars + "..." = 2047 chars). The exact length is 2047
        // because the cap drops the tail and appends the marker.
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) huge.append("x");
        stats.recordError(new RuntimeException(huge.toString()));
        assertThat(stats.lastError().length()).isEqualTo(2047);
        assertThat(stats.lastError()).endsWith("...");
    }

    @Test
    void sessionStats_reset_zeroesLastErrorAtMs(@TempDir Path cwd) {
        AetherCodeEngine eng = engineFor(cwd);
        SessionStats stats = eng.sessionStats();
        stats.recordError(new IllegalStateException("boom"));
        assertThat(stats.lastErrorAtMs()).isGreaterThan(0L);
        stats.reset();
        assertThat(stats.lastErrorAtMs()).isEqualTo(0L);
        assertThat(stats.lastError()).isEqualTo("");
    }

    @Test
    void sessionStats_toWireSnapshot_includesLastErrorAtMs(@TempDir Path cwd) {
        AetherCodeEngine eng = engineFor(cwd);
        SessionStats stats = eng.sessionStats();
        stats.recordError(new IllegalStateException("test"));
        Map<String, Object> snap = stats.toWireSnapshot();
        assertThat(snap).containsKey("last_error_at_ms");
        assertThat(snap.get("last_error")).isEqualTo("test");
        assertThat((Long) snap.get("last_error_at_ms")).isGreaterThan(0L);
    }

    @Test
    void engineHealth_reflectsRecordedError(@TempDir Path cwd) {
        AetherCodeEngine eng = engineFor(cwd);
        AetherCodeMethods m = new AetherCodeMethods(eng, n -> {});
        eng.sessionStats().recordError(new IllegalStateException("permission timeout"));
        @SuppressWarnings("unchecked")
        Map<String, Object> h = (Map<String, Object>) m.engineHealth(Map.of());
        assertThat(h.get("lastError")).isEqualTo("permission timeout");
        assertThat((Long) h.get("lastErrorAtMs")).isGreaterThan(0L);
        assertThat((Long) h.get("lastErrorForMs")).isBetween(0L, 5_000L);
    }

    @Test
    void engineHealth_marksHealthyWhenRecentActivity(@TempDir Path cwd) {
        AetherCodeEngine eng = engineFor(cwd);
        AetherCodeMethods m = new AetherCodeMethods(eng, n -> {});
        // recordQuery bumps lastActivityAtMs to now.
        eng.sessionStats().recordQuery();
        @SuppressWarnings("unchecked")
        Map<String, Object> h = (Map<String, Object>) m.engineHealth(Map.of());
        assertThat(h.get("healthy")).isEqualTo(true);
        assertThat((Long) h.get("idleForMs")).isBetween(0L, 5_000L);
    }
}
