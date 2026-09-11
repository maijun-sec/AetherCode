package org.aethercode.permission;

import org.aethercode.config.Action;
import org.aethercode.config.OpKind;
import org.aethercode.config.PermissionMatrix;
import org.aethercode.config.SkipConfirmationRegistry;
import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * verifies the skip-low listener fires exactly once when
 * the per-session skip counter crosses DOWN through the
 * configured waterline.
 */
class SkipLowListenerTest {

    private static final Path CWD = Path.of("/tmp/proj");

    @Test
    void skipLow_firesOnceWhenCrossingDownToWaterline() throws Exception {
        // Waterline = 5, arm 10 rounds. We expect ONE fire on
        // the consume that brings remaining from 6 -> 5.
        // Consumes 5->4, 4->3, ... should NOT fire.
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        p.setLowWaterline(5);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 10);

        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> lastSessionId = new AtomicReference<>("");
        AtomicInteger lastRemaining = new AtomicInteger(-1);
        p.setOnSkipLow((sessionId, remaining) -> {
            calls.incrementAndGet();
            lastSessionId.set(sessionId);
            lastRemaining.set(remaining);
        });

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx = new Tool.CallContext("s1", null, Map.of());

        // First 4 consumes: 10 -> 9 -> 8 -> 7 -> 6. All strictly
        // above waterline=5. Listener should NOT fire.
        for (int i = 0; i < 4; i++) {
            p.check(t, Map.of("file_path", "x"), ctx).get();
        }
        assertThat(calls.get())
                .as("listener not fired while remaining > waterline")
                .isEqualTo(0);

        // Consume 6 -> 5: previous (6) > waterline (5) AND new
        // (5) <= waterline (5). Listener fires once.
        p.check(t, Map.of("file_path", "x"), ctx).get();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(lastSessionId.get()).isEqualTo("s1");
        assertThat(lastRemaining.get()).isEqualTo(5);

        // Subsequent consumes: 5 -> 4 -> 3 -> ... Listener
        // does NOT fire again (already below waterline).
        for (int i = 0; i < 3; i++) {
            p.check(t, Map.of("file_path", "x"), ctx).get();
        }
        assertThat(calls.get())
                .as("listener not fired again after first crossing")
                .isEqualTo(1);
    }

    @Test
    void skipLow_firesOnceEvenWhenArmedWithFewerThanWaterline() throws Exception {
        // Arm 3 rounds with waterline 5. The very first consume
        // brings 3 -> 2; previous (3) is NOT > waterline (5), so
        // the listener does NOT fire (we only fire on a DOWN
        // crossing through the waterline, not when starting
        // below it).
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        p.setLowWaterline(5);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 3);

        AtomicInteger calls = new AtomicInteger();
        p.setOnSkipLow((sessionId, remaining) -> calls.incrementAndGet());

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx = new Tool.CallContext("s1", null, Map.of());
        for (int i = 0; i < 3; i++) {
            p.check(t, Map.of("file_path", "x"), ctx).get();
        }
        assertThat(calls.get())
                .as("listener not fired when arming below waterline")
                .isEqualTo(0);
    }

    @Test
    void skipLow_waterlineZero_disablesNotification() throws Exception {
        // Waterline = 0 disables the listener entirely.
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        p.setLowWaterline(0);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 3);

        AtomicInteger calls = new AtomicInteger();
        p.setOnSkipLow((sessionId, remaining) -> calls.incrementAndGet());

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx = new Tool.CallContext("s1", null, Map.of());
        for (int i = 0; i < 3; i++) {
            p.check(t, Map.of("file_path", "x"), ctx).get();
        }
        assertThat(calls.get())
                .as("listener disabled when waterline <= 0")
                .isEqualTo(0);
    }

    @Test
    void skipLow_notInstalled_doesNotBreakCheck() throws Exception {
        // No setOnSkipLow — the policy still works.
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        p.setLowWaterline(5);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 1);

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx = new Tool.CallContext("s1", null, Map.of());
        PermissionResult r = p.check(t, Map.of("file_path", "x"), ctx).get();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    void skipLow_failingListener_doesNotBreakCheck() throws Exception {
        // Listener throws — permission check still returns Allow
        // and the global onSkipConsumed listener still fires.
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        p.setLowWaterline(5);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 10);

        AtomicInteger globalCalls = new AtomicInteger();
        p.setOnSkipConsumed(remaining -> globalCalls.incrementAndGet());
        p.setOnSkipLow((sessionId, remaining) -> {
            throw new RuntimeException("skip-low boom");
        });

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx = new Tool.CallContext("s1", null, Map.of());
        // 10 -> 9 -> 8 -> 7 -> 6 -> 5 (would fire onSkipLow)
        for (int i = 0; i < 6; i++) {
            PermissionResult r = p.check(t, Map.of("file_path", "x"), ctx).get();
            assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        }
        // Global listener fired on every consume; the
        // failing onSkipLow did not stop it.
        assertThat(globalCalls.get()).isEqualTo(6);
    }

    @Test
    void skipLow_isolatedAcrossSessions() throws Exception {
        // Two sessions, each with their own counter and
        // listener fire. The crossing is per-session, not
        // global.
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        p.setLowWaterline(5);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 10);
        reg.set("s2", 10);

        AtomicReference<String> lastSessionId = new AtomicReference<>("");
        p.setOnSkipLow((sessionId, remaining) -> lastSessionId.set(sessionId));

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx1 = new Tool.CallContext("s1", null, Map.of());
        Tool.CallContext ctx2 = new Tool.CallContext("s2", null, Map.of());

        // s1: 4 consumes (10 -> 6), no fire.
        for (int i = 0; i < 4; i++) {
            p.check(t, Map.of("file_path", "x"), ctx1).get();
        }
        assertThat(lastSessionId.get())
                .as("no fire yet")
                .isEqualTo("");

        // s1: 1 more consume (6 -> 5), fire with sessionId="s1".
        p.check(t, Map.of("file_path", "x"), ctx1).get();
        assertThat(lastSessionId.get()).isEqualTo("s1");

        // s2: 1 consume (10 -> 9), no fire (still > 5).
        p.check(t, Map.of("file_path", "x"), ctx2).get();
        assertThat(lastSessionId.get())
                .as("s2 hasn't crossed the waterline yet")
                .isEqualTo("s1");
    }

    @Test
    void withMatrix_preservesWaterline() {
        // withMatrix carries the waterline so a config
        // reload doesn't silently reset the listener threshold.
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                new PermissionMatrix()
                        .withOverride("file_write", "**", OpKind.CREATE, Action.ASK),
                SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        p.setLowWaterline(7);
        MatrixPermissionPolicy swapped = p.withMatrix(new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ALLOW));
        assertThat(swapped.lowWaterline())
                .as("withMatrix carries the waterline")
                .isEqualTo(7);
    }

    /** Minimal {@link Tool} stub. */
    static class StubTool implements Tool {
        private final String name;
        private final boolean readOnly;
        StubTool(String name, boolean readOnly) { this.name = name; this.readOnly = readOnly; }
        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }
        @Override public boolean isReadOnly(Map<String, Object> input) { return readOnly; }
        @Override public CompletableFuture<PermissionResult> checkPermissions(
                Map<String, Object> input, CallContext ctx) {
            return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
        }
        @Override public CompletableFuture<ToolResult> call(
                Map<String, Object> input, CallContext ctx) {
            return CompletableFuture.completedFuture(ToolResult.of("ok"));
        }
    }
}
