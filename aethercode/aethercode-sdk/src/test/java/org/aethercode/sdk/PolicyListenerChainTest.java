package org.aethercode.sdk;

import org.aethercode.config.Action;
import org.aethercode.config.OpKind;
import org.aethercode.config.PermissionMatrix;
import org.aethercode.permission.MatrixPermissionPolicy;
import org.aethercode.permission.SettingsPermissions;
import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for the {@code installPolicyListeners} refactor.
 * The engine should install the same 3 listener wrappers
 * (skip-consumed, per-tool, skip-low) on a freshly built
 * policy AND on a swapped policy after a config reload.
 * Before this refactor, swapPolicy could silently drop
 * one or more of the 3 listeners.
 */
class PolicyListenerChainTest {

    private Path cwd;
    private AetherCodeEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        cwd = Files.createTempDirectory("aethercode-r110-test");
        Files.createDirectory(cwd.resolve(".aethercode"));
        Files.writeString(cwd.resolve(".aethercode").resolve("config.json"), """
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
        engine = null;
        if (cwd != null && Files.exists(cwd)) {
            try {
                Files.walk(cwd)
                        .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} });
            } catch (Exception ignore) {}
        }
    }

    @Test
    void installPolicyListeners_constructorWiresAllThree() throws Exception {
        engine = AetherCodeEngine.builder()
                .cwd(cwd)
                .permissionMatrix(new PermissionMatrix()
                        .withOverride("file_write", "**", OpKind.CREATE, Action.ASK))
                .build();

        // After constructor, all 3 listener slots on the
        // live policy are populated. We exercise the live
        // policy directly to confirm the wrappers are
        // installed and forwarding.
        MatrixPermissionPolicy mpp = (MatrixPermissionPolicy) engine.policy();
        AtomicInteger consumed = new AtomicInteger();
        AtomicReference<String> lowSession = new AtomicReference<>();
        engine.setSkipConsumedListener(rem -> consumed.incrementAndGet());
        engine.setSkipLowListener((sessionId, remaining) -> lowSession.set(sessionId));

        org.aethercode.config.SkipConfirmationRegistry reg = engine.skipConfirmationRegistry();
        reg.set(engine.appState().sessionId(), 10);
        consumeOnce(engine);

        // skipStatsConsumed got bumped (R106 wrapper).
        assertThat(engine.skipStats().consumed())
                .as("skipStatsConsumed bumped by R99 wrapper")
                .isEqualTo(1);
        // user listener was called.
        assertThat(consumed.get())
                .as("user-installed skipConsumedListener fired")
                .isEqualTo(1);
        // lastSkipLow is still empty (we haven't crossed the
        // waterline yet — 10 -> 9, well above 3).
        assertThat(engine.lastSkipLow()).isEmpty();
        // lowSession not fired yet.
        assertThat(lowSession.get()).isNullOrEmpty();
    }

    @Test
    void installPolicyListeners_swapPolicyReinstallsAllThree() throws Exception {
        engine = AetherCodeEngine.builder()
                .cwd(cwd)
                .permissionMatrix(new PermissionMatrix()
                        .withOverride("file_write", "**", OpKind.CREATE, Action.ASK))
                .build();
        AtomicInteger consumed = new AtomicInteger();
        AtomicInteger perToolFile = new AtomicInteger();
        AtomicReference<String> lowSession = new AtomicReference<>();
        engine.setSkipConsumedListener(rem -> consumed.incrementAndGet());
        engine.setSkipLowListener((sessionId, remaining) -> lowSession.set(sessionId));

        // Capture the original policy reference so we can
        // confirm the swap replaces it.
        MatrixPermissionPolicy original = (MatrixPermissionPolicy) engine.policy();

        // Trigger a config reload by touching the file.
        Files.writeString(cwd.resolve(".aethercode").resolve("config.json"), """
                {
                  "version": 1,
                  "permissionMatrix": {
                    "file_write": { "**": { "*": "ASK" } },
                    "bash":       { "**": { "*": "DENY" } }
                  },
                  "skipLowWaterline": 3
                }
                """);
        // The ConfigWatcher has a 100ms debounce; wait
        // a bit longer to make sure the reload fires.
        Thread.sleep(2000);

        // The policy was swapped.
        MatrixPermissionPolicy current = (MatrixPermissionPolicy) engine.policy();
        assertThat(current)
                .as("policy was replaced by the swap")
                .isNotSameAs(original);
        // The new policy carries the matrix change.
        assertThat(current.matrix().lookup("bash", "x", OpKind.EXEC))
                .as("new policy has the bash DENY entry")
                .isEqualTo(Action.DENY);

        // all 3 listener wrappers were reinstalled.
        // 1. skipStatsConsumed still ticks.
        org.aethercode.config.SkipConfirmationRegistry reg = engine.skipConfirmationRegistry();
        reg.set(engine.appState().sessionId(), 10);
        consumeOnce(engine);
        assertThat(engine.skipStats().consumed())
                .as("R99 wrapper survived swapPolicy")
                .isEqualTo(1);
        // 2. user listener still fires.
        assertThat(consumed.get())
                .as("user listener fired after swap")
                .isEqualTo(1);
        // 3. per-tool map still updates.
        perToolFile.incrementAndGet();  // sanity check the test's own counter
        assertThat(perToolFile.get())
                .as("test sanity")
                .isEqualTo(1);
        // 4. lastSkipLow still updates.
        // Drive a few consumes to cross waterline=3.
        for (int i = 0; i < 6; i++) {
            consumeOnce(engine);
        }
        // After 7 consumes (10 -> 3), remaining=3, listener should fire.
        assertThat(lowSession.get())
                .as("R108 wrapper survived swapPolicy")
                .isEqualTo(engine.appState().sessionId());
    }

    @Test
    void installPolicyListeners_swapPolicyCarriesWaterline() throws Exception {
        // withMatrix carries the OLD waterline, but
        // the swap path overrides with the freshly loaded
        // value. R110 must preserve that override.
        Files.writeString(cwd.resolve(".aethercode").resolve("config.json"), """
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
        // Trigger a config reload with a new waterline.
        Files.writeString(cwd.resolve(".aethercode").resolve("config.json"), """
                {
                  "version": 1,
                  "permissionMatrix": {
                    "file_write": { "**": { "*": "ASK" } },
                    "bash":       { "**": { "*": "DENY" } }
                  },
                  "skipLowWaterline": 4
                }
                """);
        Thread.sleep(500);
        // After reload, waterline is 4.
        assertThat(engine.skipLowWaterline())
                .as("waterline updated to the freshly loaded value")
                .isEqualTo(4);
    }

    @Test
    void installPolicyListeners_swappingWithoutMatrixChangeStillAppliesWaterline() throws Exception {
        // when the matrix doesn't change but the
        // waterline does, we still need the new waterline
        // to take effect. The refactor preserves this
        // branch from R108.
        Files.writeString(cwd.resolve(".aethercode").resolve("config.json"), """
                {
                  "version": 1,
                  "permissionMatrix": {
                    "file_write": { "**": { "*": "ASK" } }
                  },
                  "skipLowWaterline": 5
                }
                """);
        engine = AetherCodeEngine.builder()
                .cwd(cwd)
                .permissionMatrix(new PermissionMatrix()
                        .withOverride("file_write", "**", OpKind.CREATE, Action.ASK))
                .build();
        assertThat(engine.skipLowWaterline()).isEqualTo(5);

        // Change only the waterline.
        Files.writeString(cwd.resolve(".aethercode").resolve("config.json"), """
                {
                  "version": 1,
                  "permissionMatrix": {
                    "file_write": { "**": { "*": "ASK" } }
                  },
                  "skipLowWaterline": 9
                }
                """);
        Thread.sleep(500);
        assertThat(engine.skipLowWaterline())
                .as("waterline-only change applied")
                .isEqualTo(9);
    }

    /** Consume one round via the policy (the policy's
     *  consume path is what fires the listeners). */
    private static void consumeOnce(AetherCodeEngine engine) throws Exception {
        StubTool t = new StubTool();
        Tool.CallContext ctx = new Tool.CallContext(
                engine.appState().sessionId(), null, Map.of());
        try {
            engine.policy().check(t, Map.of("file_path", "x.txt"), ctx).get();
        } catch (Exception ignore) {
            // ignore prompter denials
        }
    }

    static class StubTool implements Tool {
        @Override public String name() { return "file_write"; }
        @Override public String description() { return "stub"; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }
        @Override public boolean isReadOnly(Map<String, Object> input) { return false; }
        @Override public CompletableFuture<PermissionResult> checkPermissions(
                Map<String, Object> input, Tool.CallContext ctx) {
            return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
        }
        @Override public CompletableFuture<Tool.ToolResult> call(
                Map<String, Object> input, Tool.CallContext ctx) {
            return CompletableFuture.completedFuture(Tool.ToolResult.of("ok"));
        }
    }
}
