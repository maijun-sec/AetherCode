package org.aethercode.sdk;

import org.aethercode.config.Action;
import org.aethercode.config.OpKind;
import org.aethercode.config.PermissionMatrix;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * integration test for the skip-low warning
 * path. The engine wires the policy's
 * {@code onSkipLow} listener to a state-tracking
 * closure that updates {@code lastSkipLow*} and
 * forwards to the user-installed
 * {@code skipLowListener} (typically the protocol
 * layer's NOTIFY_SKIP_LOW emitter).
 */
class SkipLowIntegrationTest {

    private Path cwd;
    private Path configDir;
    private AetherCodeEngine engine;
    private String originalCwd;

    @BeforeEach
    void setUp() throws Exception {
        originalCwd = System.getProperty("user.dir");
        cwd = Files.createTempDirectory("aethercode-skiplow-test");
        configDir = Files.createDirectory(cwd.resolve(".aethercode"));
        // .aethercode/config.json: waterline=3 (low value so the
        // test can drive the crossing cheaply).
        Files.writeString(configDir.resolve("config.json"), """
                {
                  "version": 1,
                  "permissionMatrix": {
                    "file_write": { "**": { "*": "ASK" } }
                  },
                  "skipLowWaterline": 3
                }
                """);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Engine has no close() yet; the watcher threads
        // are daemon and the JVM reaps them. We just
        // null out the reference so the GC can pick up
        // the engine.
        engine = null;
        if (cwd != null && Files.exists(cwd)) {
            // Best-effort cleanup.
            try {
                Files.walk(cwd)
                        .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} });
            } catch (Exception ignore) {}
        }
    }

    @Test
    void lastSkipLow_isEmptyBeforeAnyConsume() throws Exception {
        engine = AetherCodeEngine.builder()
                .cwd(cwd)
                .permissionMatrix(new PermissionMatrix()
                        .withOverride("file_write", "**", OpKind.CREATE, Action.ASK))
                .build();
        // No skip armed, no consume, no skip-low event.
        assertThat(engine.lastSkipLow()).isEmpty();
        assertThat(engine.skipLowWaterline()).isEqualTo(3);
    }

    @Test
    void lastSkipLow_firesWhenCrossingWaterline() throws Exception {
        engine = AetherCodeEngine.builder()
                .cwd(cwd)
                .permissionMatrix(new PermissionMatrix()
                        .withOverride("file_write", "**", OpKind.CREATE, Action.ASK))
                .build();
        AtomicReference<String> firedSession = new AtomicReference<>();
        AtomicReference<Integer> firedRemaining = new AtomicReference<>();
        engine.setSkipLowListener((sessionId, remaining) -> {
            firedSession.set(sessionId);
            firedRemaining.set(remaining);
        });

        // Arm 5 rounds (above waterline=3). The crossing
        // should fire when remaining drops from 4 -> 3.
        engine.skipConfirmationRegistry().set(
                engine.appState().sessionId(), 5);

        // Consume 5 -> 4: previous (5) > waterline (3) AND
        // new (4) > waterline (3). NO fire.
        consumeOnce(engine);
        assertThat(firedSession.get())
                .as("no fire while remaining > waterline")
                .isNull();

        // Consume 4 -> 3: previous (4) > waterline (3) AND
        // new (3) <= waterline (3). FIRE.
        consumeOnce(engine);
        assertThat(firedSession.get()).isEqualTo(engine.appState().sessionId());
        assertThat(firedRemaining.get()).isEqualTo(3);
        // Engine's lastSkipLow snapshot is now set.
        assertThat(engine.lastSkipLow()).isPresent();
        assertThat(engine.lastSkipLow().get().sessionId())
                .isEqualTo(engine.appState().sessionId());
        assertThat(engine.lastSkipLow().get().remaining()).isEqualTo(3);
        assertThat(engine.lastSkipLow().get().atMs()).isPositive();

        // Subsequent consumes: 3 -> 2 -> 1 -> 0. NO additional
        // fire (already below waterline).
        consumeOnce(engine);
        consumeOnce(engine);
        consumeOnce(engine);
        assertThat(firedRemaining.get())
                .as("fired only once at the crossing")
                .isEqualTo(3);
    }

    @Test
    void lastSkipLow_doesNotFireWhenWaterlineZero() throws Exception {
        // Override the config to disable (waterline=0).
        Files.writeString(configDir.resolve("config.json"), """
                {
                  "version": 1,
                  "permissionMatrix": {
                    "file_write": { "**": { "*": "ASK" } }
                  },
                  "skipLowWaterline": 0
                }
                """);
        engine = AetherCodeEngine.builder()
                .cwd(cwd)
                .permissionMatrix(new PermissionMatrix()
                        .withOverride("file_write", "**", OpKind.CREATE, Action.ASK))
                .build();
        AtomicReference<Integer> fired = new AtomicReference<>();
        engine.setSkipLowListener((sessionId, remaining) -> fired.set(remaining));

        engine.skipConfirmationRegistry().set(
                engine.appState().sessionId(), 3);
        // 3 -> 2: would normally cross waterline=3; but
        // waterline is 0 so listener is disabled.
        consumeOnce(engine);
        assertThat(fired.get())
                .as("listener disabled when waterline <= 0")
                .isNull();
        assertThat(engine.lastSkipLow()).isEmpty();
    }

    @Test
    void lastSkipLow_waterlineFromConfigPropagates() throws Exception {
        // Different waterline in config — should be picked
        // up by the engine.
        Files.writeString(configDir.resolve("config.json"), """
                {
                  "version": 1,
                  "permissionMatrix": {
                    "file_write": { "**": { "*": "ASK" } }
                  },
                  "skipLowWaterline": 7
                }
                """);
        engine = AetherCodeEngine.builder()
                .cwd(cwd)
                .permissionMatrix(new PermissionMatrix()
                        .withOverride("file_write", "**", OpKind.CREATE, Action.ASK))
                .build();
        assertThat(engine.skipLowWaterline()).isEqualTo(7);
    }

    @Test
    void lastSkipLow_failingListenerDoesNotBreakCheck() throws Exception {
        engine = AetherCodeEngine.builder()
                .cwd(cwd)
                .permissionMatrix(new PermissionMatrix()
                        .withOverride("file_write", "**", OpKind.CREATE, Action.ASK))
                .build();
        engine.setSkipLowListener((sessionId, remaining) -> {
            throw new RuntimeException("skip-low listener boom");
        });
        engine.skipConfirmationRegistry().set(
                engine.appState().sessionId(), 5);
        // All 5 consumes should succeed even though the
        // listener throws.
        for (int i = 0; i < 5; i++) {
            consumeOnce(engine);
        }
        // Engine's snapshot was still updated (the state
        // update happens before the listener forwarding).
        assertThat(engine.lastSkipLow()).isPresent();
    }

    /** Consume one round via the policy. R108: the policy's
     *  consume path is what fires the onSkipLow listener — a
     *  raw registry.consumeOne() bypasses the policy and the
     *  listener never fires. */
    private static void consumeOnce(AetherCodeEngine engine) throws Exception {
        StubTool t = new StubTool();
        org.aethercode.core.tool.Tool.CallContext ctx =
                new org.aethercode.core.tool.Tool.CallContext(
                        engine.appState().sessionId(), null, Map.of());
        try {
            engine.policy().check(t, Map.of("file_path", "x.txt"), ctx).get();
        } catch (Exception ignore) {
            // The matrix says ASK; the test bypasses the
            // prompter by routing through the skip registry.
            // The prompter would deny, but we don't care
            // — we only care that consumeOne ran first.
        }
    }

    static class StubTool implements org.aethercode.core.tool.Tool {
        @Override public String name() { return "file_write"; }
        @Override public String description() { return "stub"; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }
        @Override public boolean isReadOnly(Map<String, Object> input) { return false; }
        @Override public java.util.concurrent.CompletableFuture<org.aethercode.core.permission.PermissionResult> checkPermissions(
                Map<String, Object> input, org.aethercode.core.tool.Tool.CallContext ctx) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new org.aethercode.core.permission.PermissionResult.Allow(input));
        }
        @Override public java.util.concurrent.CompletableFuture<org.aethercode.core.tool.Tool.ToolResult> call(
                Map<String, Object> input, org.aethercode.core.tool.Tool.CallContext ctx) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    org.aethercode.core.tool.Tool.ToolResult.of("ok"));
        }
    }
}
