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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contract test for the new {@link PermissionMode#ASK_BEFORE_TOOL}
 * mode's behaviour in {@link ProjectPermissionPolicy}.
 *
 * <p>ASK_BEFORE_TOOL is semantically identical to
 * {@link PermissionMode#DEFAULT} (both route every non-read-only
 * tool call through the prompter), but the name is more discoverable
 * in the TUI's status bar / command palette and is the new
 * recommend value for empty / exploration projects.
 *
 * <p>The test pins the four corner cases:
 * <ol>
 *   <li>ASK_BEFORE_TOOL + bash → prompter is invoked</li>
 *   <li>ASK_BEFORE_TOOL + read-only tool → auto-allow (no prompt)</li>
 *   <li>ASK_BEFORE_TOOL + allow-rule → allow-rule wins (no prompt)</li>
 *   <li>ASK_BEFORE_TOOL + deny-rule → deny-rule wins</li>
 * </ol>
 * Compare with the equivalent DEFAULT-mode paths to confirm the
 * two modes are interchangeable.
 */
class ProjectPermissionPolicyR163Test {

    private final Tool bash = Tools.build(new ToolDef("bash", "Bash", Map.of(),
            (in, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));

    private static Tool readOnlyTool() {
        Tool inner = Tools.build(new ToolDef("read", "R", Map.of(),
                (in, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));
        return new Tool() {
            @Override public String name() { return "read"; }
            @Override public String description() { return "R"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public boolean isReadOnly(Map<String, Object> input) { return true; }
            @Override public CompletableFuture<PermissionResult> checkPermissions(
                    Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            @Override public CompletableFuture<ToolResult> call(
                    Map<String, Object> input, CallContext ctx) {
                return inner.call(input, ctx);
            }
        };
    }

    @Test
    void askBeforeTool_routesThroughPrompter() {
        AtomicInteger prompterCalls = new AtomicInteger(0);
        ToolPermissionPrompter prompter = (t, i, q) -> {
            prompterCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        };
        SettingsPermissions sp = SettingsPermissions.empty();
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ASK_BEFORE_TOOL, prompter);
        PermissionResult r = p.check(bash, Map.of("command", "ls"), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(prompterCalls.get()).isEqualTo(1);
    }

    @Test
    void askBeforeTool_behavesIdenticallyToDefault() {
        // Run the same scenario with both modes and confirm the
        // observable behaviour is the same. The prompter is
        // counted in both cases.
        ToolPermissionPrompter counting = (t, i, q) -> CompletableFuture.completedFuture(
                new PermissionResult.Allow(i));

        SettingsPermissions sp = SettingsPermissions.empty();

        // DEFAULT mode
        AtomicInteger defaultCalls = new AtomicInteger(0);
        PermissionPolicy pd = new ProjectPermissionPolicy(sp, PermissionMode.DEFAULT, (t, i, q) -> {
            defaultCalls.incrementAndGet();
            return counting.ask(t, i, q);
        });
        pd.check(bash, Map.of("command", "ls"), Tool.CallContext.of("s")).join();

        // ASK_BEFORE_TOOL mode
        AtomicInteger askBeforeCalls = new AtomicInteger(0);
        PermissionPolicy pa = new ProjectPermissionPolicy(sp, PermissionMode.ASK_BEFORE_TOOL, (t, i, q) -> {
            askBeforeCalls.incrementAndGet();
            return counting.ask(t, i, q);
        });
        pa.check(bash, Map.of("command", "ls"), Tool.CallContext.of("s")).join();

        assertThat(defaultCalls.get()).isEqualTo(askBeforeCalls.get()).isEqualTo(1);
    }

    @Test
    void askBeforeTool_readOnlyToolBypassesAsk() {
        // Read-only tools never need a prompt regardless of mode.
        // (R163: this is a defence-in-depth: the policy is a single
        // switch on mode, but a read-only tool reaches the resolver
        // with isReadOnly=true and short-circuits before the mode
        // arm is reached.)
        AtomicInteger prompterCalls = new AtomicInteger(0);
        ToolPermissionPrompter prompter = (t, i, q) -> {
            prompterCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        };
        SettingsPermissions sp = SettingsPermissions.empty();
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ASK_BEFORE_TOOL, prompter);
        PermissionResult r = p.check(readOnlyTool(), Map.of(), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(prompterCalls.get()).isEqualTo(0);
    }

    @Test
    void askBeforeTool_allowRuleWins() {
        // An allow-rule still bypasses the prompter, same as DEFAULT.
        SettingsPermissions sp = SettingsPermissions.empty();
        sp.allow = java.util.List.of(new Rule("bash", "git status", null));
        AtomicInteger prompterCalls = new AtomicInteger(0);
        ToolPermissionPrompter prompter = (t, i, q) -> {
            prompterCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new PermissionResult.Allow(i));
        };
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ASK_BEFORE_TOOL, prompter);
        PermissionResult r = p.check(bash, Map.of("command", "git status"), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(prompterCalls.get()).isEqualTo(0);
    }

    @Test
    void askBeforeTool_denyRuleWins() {
        // A deny-rule beats the prompter, same as DEFAULT.
        SettingsPermissions sp = SettingsPermissions.empty();
        sp.deny = java.util.List.of(new Rule("bash", "rm -rf /", "danger"));
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ASK_BEFORE_TOOL, null);
        PermissionResult r = p.check(bash, Map.of("command", "rm -rf /"), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    void askBeforeTool_askRuleTriggersPrompter() {
        // An ask-rule forces the prompter to be called even if
        // a later allow-rule would have matched.
        SettingsPermissions sp = SettingsPermissions.empty();
        sp.ask = java.util.List.of(new Rule("bash", "git *", null));
        AtomicInteger prompterCalls = new AtomicInteger(0);
        ToolPermissionPrompter prompter = (t, i, q) -> {
            prompterCalls.incrementAndGet();
            return CompletableFuture.completedFuture(PermissionResult.Deny.of("test deny"));
        };
        PermissionPolicy p = new ProjectPermissionPolicy(sp, PermissionMode.ASK_BEFORE_TOOL, prompter);
        PermissionResult r = p.check(bash, Map.of("command", "git status"), Tool.CallContext.of("s")).join();
        assertThat(r).isInstanceOf(PermissionResult.Deny.class);
        assertThat(prompterCalls.get()).isEqualTo(1);
    }
}
