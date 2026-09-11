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
 * verifies the skip-consumed listener fires on each
 * consumption and forwards the remaining count.
 */
class SkipConsumedListenerTest {

    private static final Path CWD = Path.of("/tmp/proj");

    @Test
    void listener_firesOncePerConsume_withRemaining() throws Exception {
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                // The "ask" prompter denies so we can assert the matrix
                // ASK -> consumed-skip -> ALLOW path independently of
                // the inner prompter.
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("user said no")),
                CWD);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 3);

        AtomicInteger listenerCalls = new AtomicInteger();
        AtomicInteger lastRemaining = new AtomicInteger(-1);
        p.setOnSkipConsumed(remaining -> {
            listenerCalls.incrementAndGet();
            lastRemaining.set(remaining);
        });

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx = new Tool.CallContext("s1", null, Map.of());

        // Three consumes, listener fires with remaining = 2, 1, 0.
        for (int expected = 2; expected >= 0; expected--) {
            PermissionResult r = p.check(t, Map.of("file_path", "x.txt"), ctx).get();
            assertThat(r).isInstanceOf(PermissionResult.Allow.class);
            assertThat(listenerCalls.get()).isEqualTo(3 - expected);
            assertThat(lastRemaining.get()).isEqualTo(expected);
        }

        // After exhaustion, the listener does NOT fire (the inner policy
        // handles the call; the skip path is bypassed).
        int beforeNext = listenerCalls.get();
        PermissionResult r4 = p.check(t, Map.of("file_path", "y.txt"), ctx).get();
        assertThat(r4).isInstanceOf(PermissionResult.Deny.class);
        assertThat(listenerCalls.get())
                .as("listener not fired after counter exhausted")
                .isEqualTo(beforeNext);
    }

    @Test
    void listener_failingListener_doesNotBreakCheck() throws Exception {
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 1);

        // Listener throws — should NOT prevent the permission check
        // from returning Allow.
        p.setOnSkipConsumed(remaining -> { throw new RuntimeException("boom"); });

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx = new Tool.CallContext("s1", null, Map.of());
        PermissionResult r = p.check(t, Map.of("file_path", "x"), ctx).get();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    void listener_notInstalled_doesNotBreakCheck() throws Exception {
        // No listener set — the policy still works correctly.
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 1);

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx = new Tool.CallContext("s1", null, Map.of());
        PermissionResult r = p.check(t, Map.of("file_path", "x"), ctx).get();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    void listener_isolatedAcrossSessions() throws Exception {
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 5);
        // s2 has no skip.

        AtomicInteger totalCalls = new AtomicInteger();
        p.setOnSkipConsumed(r -> totalCalls.incrementAndGet());

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx1 = new Tool.CallContext("s1", null, Map.of());
        Tool.CallContext ctx2 = new Tool.CallContext("s2", null, Map.of());

        // s1: skip applies, listener fires.
        assertThat(p.check(t, Map.of("file_path", "x"), ctx1).get())
                .isInstanceOf(PermissionResult.Allow.class);
        // s2: no skip, falls through to prompter.
        assertThat(p.check(t, Map.of("file_path", "x"), ctx2).get())
                .isInstanceOf(PermissionResult.Deny.class);
        assertThat(totalCalls.get())
                .as("listener only fired for the session with a counter")
                .isEqualTo(1);
    }

    @Test
    void perToolListener_firesWithToolName_andRemaining() throws Exception {
        // the per-tool listener receives the tool name + the
        // remaining count after the consume. Three consumes on the
        // same tool should fire with remaining = 2, 1, 0.
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 3);

        AtomicInteger listenerCalls = new AtomicInteger();
        AtomicReference<String> lastTool = new AtomicReference<>("");
        AtomicInteger lastRemaining = new AtomicInteger(-1);
        p.setOnToolSkipConsumed((toolName, remaining) -> {
            listenerCalls.incrementAndGet();
            lastTool.set(toolName);
            lastRemaining.set(remaining);
        });

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx = new Tool.CallContext("s1", null, Map.of());

        for (int expected = 2; expected >= 0; expected--) {
            PermissionResult r = p.check(t, Map.of("file_path", "x"), ctx).get();
            assertThat(r).isInstanceOf(PermissionResult.Allow.class);
            assertThat(listenerCalls.get()).isEqualTo(3 - expected);
            assertThat(lastTool.get())
                    .as("per-tool listener received the tool name")
                    .isEqualTo("file_write");
            assertThat(lastRemaining.get())
                    .as("per-tool listener received the remaining count")
                    .isEqualTo(expected);
        }
    }

    @Test
    void perToolListener_isolatedPerTool() throws Exception {
        // the listener is fired per call. Each tool's count
        // is reported independently — a later listener call for
        // tool A doesn't carry tool B's count.
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK)
                .withOverride("bash", "**", OpKind.EXEC, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 5);

        AtomicReference<String> lastTool = new AtomicReference<>("");
        AtomicInteger lastRemaining = new AtomicInteger(-1);
        p.setOnToolSkipConsumed((toolName, remaining) -> {
            lastTool.set(toolName);
            lastRemaining.set(remaining);
        });

        StubTool fileWrite = new StubTool("file_write", false);
        StubTool bash = new StubTool("bash", false);
        Tool.CallContext ctx = new Tool.CallContext("s1", null, Map.of());

        // 1. file_write call -> tool="file_write", remaining=4
        p.check(fileWrite, Map.of("file_path", "x"), ctx).get();
        assertThat(lastTool.get()).isEqualTo("file_write");
        assertThat(lastRemaining.get()).isEqualTo(4);

        // 2. bash call -> tool="bash", remaining=3 (independent)
        p.check(bash, Map.of("command", "ls"), ctx).get();
        assertThat(lastTool.get()).isEqualTo("bash");
        assertThat(lastRemaining.get())
                .as("bash listener got its own remaining, not file_write's")
                .isEqualTo(3);

        // 3. file_write again -> tool="file_write", remaining=2
        p.check(fileWrite, Map.of("file_path", "y"), ctx).get();
        assertThat(lastTool.get()).isEqualTo("file_write");
        assertThat(lastRemaining.get()).isEqualTo(2);
    }

    @Test
    void perToolListener_notInstalled_doesNotBreakCheck() throws Exception {
        // not installing the per-tool listener must not
        // affect the permission check or the global skip path.
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 1);
        // No setOnToolSkipConsumed.

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx = new Tool.CallContext("s1", null, Map.of());
        PermissionResult r = p.check(t, Map.of("file_path", "x"), ctx).get();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    void perToolListener_failingListener_doesNotBreakCheck() throws Exception {
        // a per-tool listener that throws must not break
        // the permission check (and must not stop the global
        // listener from firing).
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 2);

        AtomicInteger globalCalls = new AtomicInteger();
        p.setOnSkipConsumed(remaining -> globalCalls.incrementAndGet());
        p.setOnToolSkipConsumed((toolName, remaining) -> {
            throw new RuntimeException("per-tool boom");
        });

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx = new Tool.CallContext("s1", null, Map.of());

        // Both calls succeed even though the per-tool listener throws.
        PermissionResult r1 = p.check(t, Map.of("file_path", "x"), ctx).get();
        assertThat(r1).isInstanceOf(PermissionResult.Allow.class);
        PermissionResult r2 = p.check(t, Map.of("file_path", "y"), ctx).get();
        assertThat(r2).isInstanceOf(PermissionResult.Allow.class);
        // The global listener was NOT affected by the per-tool failure.
        assertThat(globalCalls.get())
                .as("global listener fired both times despite per-tool failure")
                .isEqualTo(2);
    }

    @Test
    void perToolListener_firesAlongsideGlobalListener() throws Exception {
        // the per-tool listener fires INDEPENDENTLY of the
        // global listener — installing one does not replace the
        // other. Both must fire on every consume.
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT,
                (tool, input, q) -> CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no")),
                CWD);
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 4);

        AtomicInteger globalCalls = new AtomicInteger();
        AtomicInteger perToolCalls = new AtomicInteger();
        p.setOnSkipConsumed(remaining -> globalCalls.incrementAndGet());
        p.setOnToolSkipConsumed((toolName, remaining) -> perToolCalls.incrementAndGet());

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx = new Tool.CallContext("s1", null, Map.of());

        for (int i = 0; i < 4; i++) {
            PermissionResult r = p.check(t, Map.of("file_path", "x"), ctx).get();
            assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        }
        assertThat(globalCalls.get()).isEqualTo(4);
        assertThat(perToolCalls.get()).isEqualTo(4);
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
