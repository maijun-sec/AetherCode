package org.aethercode.permission;

import org.aethercode.config.Action;
import org.aethercode.config.OpKind;
import org.aethercode.config.PermissionMatrix;
import org.aethercode.config.defaults.DefaultMatrix;
import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MatrixPermissionPolicy}. Verifies that the matrix
 * short-circuits before the inner {@link ProjectPermissionPolicy}.
 */
class MatrixPermissionPolicyTest {

    private static final Path CWD = Path.of("/tmp/proj");

    @Test
    void matrixDeny_isReturnedImmediately_evenInBypassMode() throws Exception {
        // Build a policy that, in BYPASS mode, would normally allow anything,
        // but the matrix denies file_write to /etc.
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "/etc/**", OpKind.CREATE, Action.DENY)
                .withOverride("file_write", "/etc/**", OpKind.MODIFY, Action.DENY);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.BYPASS_PERMISSIONS, null, CWD);

        StubTool t = new StubTool("file_write", false);
        CompletableFuture<PermissionResult> f = p.check(t, Map.of("file_path", "/etc/passwd"), null);
        PermissionResult r = f.get();
        assertThat(r).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    void matrixAllow_isReturnedImmediately_evenWithAskRules() throws Exception {
        // The legacy rules would ASK for everything; the matrix says allow.
        Rule ask = new Rule("file_write", null, "ask by default");
        SettingsPermissions perms = new SettingsPermissions();
        perms.ask = List.of(ask);
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "scripts/build/**", OpKind.CREATE, Action.ALLOW);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, perms, PermissionMode.DEFAULT, null, CWD);

        StubTool t = new StubTool("file_write", false);
        CompletableFuture<PermissionResult> f = p.check(t,
                Map.of("file_path", "scripts/build/seed.sh"), null);
        PermissionResult r = f.get();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    void matrixAsk_fallsThroughToInnerPolicy_defaultModeReturnsAsk() throws Exception {
        // Matrix says ASK -> fall through to inner -> mode DEFAULT -> ask.
        // With no prompter, ask is auto-allowed (because the tool is NOT
        // isReadOnly in this case it's a write), so use a tool that is
        // isReadOnly=false and the inner policy's resolveAsk returns
        // Allow when prompter is null AND tool is read-only. Use a non-
        // read-only tool and a null prompter: result is Allow (per the
        // ProjectPermissionPolicy's "ask auto-allowed" branch).
        // To force a non-Allow result, we need a real prompter. We mock one.
        AtomicInteger askCount = new AtomicInteger();
        ToolPermissionPrompter prompter = new ToolPermissionPrompter() {
            @Override
            public CompletableFuture<PermissionResult> ask(
                    Tool tool, Map<String, Object> input, String question) {
                askCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("user said no"));
            }
        };
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT, prompter, CWD);

        StubTool t = new StubTool("file_write", false);
        PermissionResult r = p.check(t, Map.of("file_path", "anywhere"), null).get();
        assertThat(askCount.get()).isEqualTo(1);
        assertThat(r).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    void readOnlyTool_bypassesMatrix() throws Exception {
        // A read-only tool (file_read) is always allowed even if the matrix
        // would say ASK. This is defence in depth.
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_read", "/etc/**", OpKind.READ, Action.DENY);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT, null, CWD);

        StubTool t = new StubTool("file_read", true);
        PermissionResult r = p.check(t, Map.of("file_path", "/etc/passwd"), null).get();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    void defaultMatrix_loadedCorrectly() {
        // Sanity: the default matrix recognises the standard tools.
        PermissionMatrix m = DefaultMatrix.build();
        assertThat(m.toolNames()).contains("file_read", "file_write", "file_edit", "bash");
    }

    @Test
    void bashDelete_isDeniedByDefaultMatrix() throws Exception {
        // The default matrix's bash row maps DELETE -> DENY.
        PermissionMatrix m = DefaultMatrix.build();
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.BYPASS_PERMISSIONS, null, CWD);

        StubTool t = new StubTool("bash", false);
        // rm with -rf triggers DESTRUCTIVE_FLAGS -> DELETE in OpKindDetector
        PermissionResult r = p.check(t, Map.of("command", "rm -rf /tmp/foo"), null).get();
        assertThat(r).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    void withMatrix_preservesRulesAndMode() throws Exception {
        // Replacing the matrix does not change the inner rules / mode.
        PermissionMatrix m1 = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.DENY);
        PermissionMatrix m2 = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ALLOW);
        MatrixPermissionPolicy p1 = new MatrixPermissionPolicy(
                m1, SettingsPermissions.empty(), PermissionMode.BYPASS_PERMISSIONS, null, CWD);
        MatrixPermissionPolicy p2 = p1.withMatrix(m2);

        StubTool t = new StubTool("file_write", false);
        // p1 denies, p2 allows the same call
        assertThat(p1.check(t, Map.of("file_path", "x.txt"), null).get())
                .isInstanceOf(PermissionResult.Deny.class);
        assertThat(p2.check(t, Map.of("file_path", "x.txt"), null).get())
                .isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    void nullMatrix_isEquivalentToEmptyMatrix() throws Exception {
        // Pass null -> policy falls through to inner for every call.
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                null, SettingsPermissions.empty(), PermissionMode.BYPASS_PERMISSIONS, null, CWD);
        StubTool t = new StubTool("bash", false);
        // BYPASS allows everything (and tool is not read-only, so the
        // matrix.bypass-allow would not fire; the matrix is empty so we
        // fall through to BYPASS which also allows).
        PermissionResult r = p.check(t, Map.of("command", "echo hi"), null).get();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    void skipConfirmationRegistry_shortCircuitsAskToAllow() throws Exception {
        // Matrix says ASK on a write. With skip-rounds = 2, the first two
        // calls are auto-allowed without consulting the prompter. The third
        // call falls through to the inner policy (which has no prompter and
        // the tool is not read-only, so the inner allows via the
        // null-prompter path... actually, the inner ProjectPermissionPolicy
        // returns Allow when prompter is null and the tool is non-read-only?
        // No: the inner returns Allow when prompter is null OR tool is
        // read-only. Let me re-check by setting a deny-prompter that fires
        // only after the skip counter is exhausted.
        org.aethercode.config.SkipConfirmationRegistry reg =
                new org.aethercode.config.SkipConfirmationRegistry();
        AtomicInteger deniedAfterSkip = new AtomicInteger();
        ToolPermissionPrompter prompter = new ToolPermissionPrompter() {
            @Override
            public CompletableFuture<PermissionResult> ask(
                    Tool tool, Map<String, Object> input, String question) {
                deniedAfterSkip.incrementAndGet();
                return CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("user said no after skip"));
            }
        };
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT, prompter, CWD);
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 2);

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx = new Tool.CallContext("s1", null, Map.of());

        // First two calls: skip applies, no prompt, Allow.
        assertThat(p.check(t, Map.of("file_path", "x.txt"), ctx).get())
                .isInstanceOf(PermissionResult.Allow.class);
        assertThat(p.check(t, Map.of("file_path", "y.txt"), ctx).get())
                .isInstanceOf(PermissionResult.Allow.class);
        assertThat(deniedAfterSkip.get())
                .as("prompter not yet consulted").isEqualTo(0);

        // Third call: counter is 0, falls through to the prompter.
        assertThat(p.check(t, Map.of("file_path", "z.txt"), ctx).get())
                .isInstanceOf(PermissionResult.Deny.class);
        assertThat(deniedAfterSkip.get())
                .as("prompter consulted once after skip exhausted").isEqualTo(1);
    }

    @Test
    void skipConfirmationRegistry_isolatedPerSession() throws Exception {
        // Two sessions; only s1 has skip-rounds. s2 still prompts.
        org.aethercode.config.SkipConfirmationRegistry reg =
                new org.aethercode.config.SkipConfirmationRegistry();
        AtomicInteger promptCount = new AtomicInteger();
        ToolPermissionPrompter prompter = new ToolPermissionPrompter() {
            @Override
            public CompletableFuture<PermissionResult> ask(
                    Tool tool, Map<String, Object> input, String question) {
                promptCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("no"));
            }
        };
        PermissionMatrix m = new PermissionMatrix()
                .withOverride("file_write", "**", OpKind.CREATE, Action.ASK);
        MatrixPermissionPolicy p = new MatrixPermissionPolicy(
                m, SettingsPermissions.empty(), PermissionMode.DEFAULT, prompter, CWD);
        p.setSkipConfirmationRegistry(reg);
        reg.set("s1", 1);

        StubTool t = new StubTool("file_write", false);
        Tool.CallContext ctx1 = new Tool.CallContext("s1", null, Map.of());
        Tool.CallContext ctx2 = new Tool.CallContext("s2", null, Map.of());

        // s1: skip applies.
        assertThat(p.check(t, Map.of("file_path", "x"), ctx1).get())
                .isInstanceOf(PermissionResult.Allow.class);
        // s2: no skip, prompt fires.
        assertThat(p.check(t, Map.of("file_path", "x"), ctx2).get())
                .isInstanceOf(PermissionResult.Deny.class);
        assertThat(promptCount.get()).isEqualTo(1);
    }

    /** Minimal {@link Tool} stub. The only methods we exercise are name() and isReadOnly(). */
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
