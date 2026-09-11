package org.aethercode.permission;

import org.aethercode.core.engine.PermissionPolicy;
import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectPermissionPolicyTest {

    private final Tool bash = Tools.build(new ToolDef("bash", "Bash", Map.of(),
            (in, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));

    @Test
    void denyRuleBeatsAllow() {
        SettingsPermissions sp = SettingsPermissions.empty();
        sp.deny = java.util.List.of(new Rule("bash", "rm -rf /", "danger"));
        sp.allow = java.util.List.of(new Rule("*", null, null));
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.DEFAULT, null);
        PermissionResult r = p.check(bash, Map.of("command", "rm -rf /"), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    void allowRuleBypassesAsk() {
        SettingsPermissions sp = SettingsPermissions.empty();
        sp.allow = java.util.List.of(new Rule("bash", "git status", null));
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.DEFAULT,
                (t, i, q) -> { throw new RuntimeException("should not be called"); });
        PermissionResult r = p.check(bash, Map.of("command", "git status"), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    void readOnlyToolBypassesAsk() {
        Tool readOnly = Tools.build(new ToolDef("read", "R", Map.of(),
                (in, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));
        // Wrap it so isReadOnly returns true.
        Tool ro = new Tool() {
            public String name() { return "read"; }
            public String description() { return "R"; }
            public Map<String, Object> inputSchema() { return Map.of(); }
            public boolean isReadOnly(Map<String, Object> input) { return true; }
            public CompletableFuture<PermissionResult> checkPermissions(Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            public CompletableFuture<ToolResult> call(Map<String, Object> input, CallContext ctx) {
                return readOnly.call(input, ctx);
            }
        };
        SettingsPermissions sp = SettingsPermissions.empty();
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.DEFAULT, null);
        PermissionResult r = p.check(ro, Map.of(), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    void bypassPermissionsAllowsEverything() {
        SettingsPermissions sp = SettingsPermissions.empty();
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.BYPASS_PERMISSIONS, null);
        PermissionResult r = p.check(bash, Map.of("command", "rm -rf /"), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    // ------------------------------------------------------------------
    // ACCEPT_TASK mode tests
    // ------------------------------------------------------------------

    /**
     * a call with no subTaskId in its context auto-allows
     * (we have no task tracking, so we can't make a meaningful
     * boundary decision — defaulting to allow matches the
     * spirit of "no mid-task stops" and avoids spurious prompts).
     */
    @Test
    void acceptTaskAutoAllowsWhenNoSubTaskId() {
        SettingsPermissions sp = SettingsPermissions.empty();
        // prompter should never be called
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ACCEPT_TASK,
                (t, i, q) -> { throw new RuntimeException("should not ask"); });
        PermissionResult r = p.check(bash, Map.of("command", "ls -la"), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    /**
     * a call with a subTaskId that matches the policy's
     * current sub-task id auto-allows. This is the "inside a
     * task" case — the user has already approved this scope.
     */
    @Test
    void acceptTaskAutoAllowsWhenSubTaskIdMatches() {
        SettingsPermissions sp = SettingsPermissions.empty();
        ProjectPermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ACCEPT_TASK,
                (t, i, q) -> { throw new RuntimeException("should not ask"); });
        p.setCurrentSubTaskId("0:write_file");
        Tool.CallContext ctx = new Tool.CallContext("s", null, Map.of("subTaskId", "0:write_file"));
        PermissionResult r = p.check(bash, Map.of("command", "echo hi"), ctx).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    /**
     * a call with a subTaskId that does NOT match the
     * policy's current sub-task id asks. This is the "task
     * boundary" — the user gets one decision prompt and (if
     * they allow) the policy's current id is then advanced to
     * the new value.
     */
    @Test
    void acceptTaskAsksOnNewSubTaskBoundary() {
        SettingsPermissions sp = SettingsPermissions.empty();
        boolean[] asked = { false };
        ProjectPermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ACCEPT_TASK,
                (t, i, q) -> { asked[0] = true; return CompletableFuture.completedFuture(new PermissionResult.Allow(i)); });
        p.setCurrentSubTaskId("0:write_file");
        Tool.CallContext ctx = new Tool.CallContext("s", null, Map.of("subTaskId", "0:read_file"));
        PermissionResult r = p.check(bash, Map.of("command", "echo hi"), ctx).join();
        assertThat(asked[0]).isTrue();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    /**
     * a call with a subTaskId but no current sub-task set
     * yet (the first call of a new query) also asks — we don't
     * know if the user is okay with the scope, so we surface one
     * prompt at the boundary.
     */
    @Test
    void acceptTaskAsksOnFirstCallOfNewQuery() {
        SettingsPermissions sp = SettingsPermissions.empty();
        boolean[] asked = { false };
        ProjectPermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ACCEPT_TASK,
                (t, i, q) -> { asked[0] = true; return CompletableFuture.completedFuture(new PermissionResult.Allow(i)); });
        // Note: no setCurrentSubTaskId — fresh query.
        Tool.CallContext ctx = new Tool.CallContext("s", null, Map.of("subTaskId", "0:write_file"));
        PermissionResult r = p.check(bash, Map.of("command", "echo hi"), ctx).join();
        assertThat(asked[0]).isTrue();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    /**
     * read-only tools always auto-allow regardless of the
     * mode (mirrors the existing resolveAsk behaviour). A read
     * tool inside ACCEPT_TASK mode with no sub-task id is
     * allowed without going through the prompter.
     */
    @Test
    void acceptTaskReadOnlyToolAutoAllows() {
        Tool readOnly = new Tool() {
            public String name() { return "read"; }
            public String description() { return "R"; }
            public Map<String, Object> inputSchema() { return Map.of(); }
            public boolean isReadOnly(Map<String, Object> input) { return true; }
            public CompletableFuture<PermissionResult> checkPermissions(Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            public CompletableFuture<ToolResult> call(Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(Tool.ToolResult.of("ok"));
            }
        };
        SettingsPermissions sp = SettingsPermissions.empty();
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ACCEPT_TASK,
                (t, i, q) -> { throw new RuntimeException("should not ask for read-only"); });
        PermissionResult r = p.check(readOnly, Map.of(), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
    }

    // ------------------------------------------------------------------
    // withMode swap
    // ------------------------------------------------------------------

    /**
     * a policy constructed with DEFAULT mode and then
     * swapped to ACCEPT_TASK must auto-allow subsequent calls. This
     * is the bug the user hit on 0.2.1: {@code setPermissionMode}
     * used to only update {@code AppState.permissionMode} so the
     * live policy kept DEFAULT, and every tool call still asked
     * despite the status bar showing "mode = ACCEPT_TASK".
     */
    @Test
    void withModeSwapChangesVerdictOnLaterCheck() {
        SettingsPermissions sp = SettingsPermissions.empty();
        boolean[] asked = { false };
        ProjectPermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.DEFAULT,
                (t, i, q) -> { asked[0] = true; return CompletableFuture.completedFuture(PermissionResult.Deny.of("user said no")); });
        // First call under DEFAULT — must ask, must be denied.
        PermissionResult r1 = p.check(bash, Map.of("command", "ls"), Tool.CallContext.of("s")).join();
        assertThat(asked[0]).isTrue();
        assertThat(((PermissionResult.Deny) r1).message()).contains("user said no");
        // Swap to ACCEPT_TASK via withMode — the new policy must
        // auto-allow without invoking the prompter.
        ProjectPermissionPolicy p2 = p.withMode(PermissionMode.ACCEPT_TASK);
        // Reset the asked counter; the swapped policy's prompter
        // is the SAME object, so the flag will be flipped only
        // if a call actually routes through resolveAsk.
        asked[0] = false;
        PermissionResult r2 = p2.check(bash, Map.of("command", "ls"), Tool.CallContext.of("s")).join();
        assertThat(asked[0]).as("ACCEPT_TASK must NOT call the prompter for a no-subTaskId call").isFalse();
        assertThat(r2).isInstanceOf(PermissionResult.Allow.class);
        // Old policy reference is unaffected (we didn't mutate it).
        asked[0] = false;
        PermissionResult r3 = p.check(bash, Map.of("command", "ls"), Tool.CallContext.of("s")).join();
        assertThat(asked[0]).isTrue();
        assertThat(((PermissionResult.Deny) r3).message()).contains("user said no");
    }
}
