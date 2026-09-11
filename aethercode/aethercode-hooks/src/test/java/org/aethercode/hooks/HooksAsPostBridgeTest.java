package org.aethercode.hooks;

import org.aethercode.core.app.AppState;
import org.aethercode.core.engine.StreamingToolExecutor;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.aethercode.hooks.builtin.EditErrorRecoveryHook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * end-to-end test for the {@code Hooks.asPostBridge}
 * mutation path. Registers a hook that returns
 * {@code ContinueWithResult}, runs the bridge, and asserts
 * the bridge's {@code runWithOutcome} returns an
 * {@code Outcome.replace(...)} carrying the hook's body.
 *
 * <p>This is the integration test for the contract:
 * <ul>
 *   <li>Hook returns ContinueWithResult → bridge returns
 *       Outcome.replace</li>
 *   <li>Hook returns Continue → bridge returns
 *       Outcome.continue_()</li>
 *   <li>Hook returns Block → bridge returns
 *       Outcome.block()</li>
 * </ul>
 */
class HooksAsPostBridgeTest {

    @Test
    void continueWithResultTriggersOutcomeReplace(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        HookRegistry reg = new HookRegistry();
        reg.register(new EditErrorRecoveryHook());
        StreamingToolExecutor.HookBridge bridge = Hooks.asPostBridge(reg);
        Tool.ToolResult original = new Tool.ToolResult(
                "old_string not found in /tmp/foo.txt", List.of(), true);
        StreamingToolExecutor.HookBridge.Outcome out = bridge.runWithOutcome(
                StreamingToolExecutor.HookBridge.Phase.POST,
                dummyTool("file_edit"),
                "call-1",
                Map.of("file_path", "/tmp/foo.txt"),
                original,
                appState);
        assertThat(out).isNotNull();
        assertThat(out.verdict()).isEqualTo(StreamingToolExecutor.HookBridge.Verdict.CONTINUE);
        assertThat(out.newResult()).isNotNull();
        // The replacement preserves isError (the edit still failed).
        assertThat(out.newResult().isError()).isTrue();
        // And carries the appended hint.
        assertThat(out.newResult().output().toString())
                .contains("old_string not found")
                .contains("IMMEDIATE ACTION REQUIRED");
    }

    @Test
    void continueYieldsNoReplacement(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        // Register a no-op hook that always returns Continue.
        HookRegistry reg = new HookRegistry();
        reg.register(new Hook() {
            @Override public Kind kind() { return Kind.POST_TOOL_USE; }
            @Override public CompletableFuture<Outcome> run(HookContext ctx) {
                return CompletableFuture.completedFuture(new Outcome.Continue());
            }
        });
        StreamingToolExecutor.HookBridge bridge = Hooks.asPostBridge(reg);
        StreamingToolExecutor.HookBridge.Outcome out = bridge.runWithOutcome(
                StreamingToolExecutor.HookBridge.Phase.POST,
                dummyTool("bash"),
                "call-2",
                Map.of("command", "ls"),
                Tool.ToolResult.of("hi"),
                appState);
        assertThat(out.verdict()).isEqualTo(StreamingToolExecutor.HookBridge.Verdict.CONTINUE);
        assertThat(out.newResult()).isNull();
    }

    @Test
    void blockYieldsBlockVerdict(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        HookRegistry reg = new HookRegistry();
        reg.register(new Hook() {
            @Override public Kind kind() { return Kind.POST_TOOL_USE; }
            @Override public CompletableFuture<Outcome> run(HookContext ctx) {
                return CompletableFuture.completedFuture(new Outcome.Block("no thanks"));
            }
        });
        StreamingToolExecutor.HookBridge bridge = Hooks.asPostBridge(reg);
        StreamingToolExecutor.HookBridge.Outcome out = bridge.runWithOutcome(
                StreamingToolExecutor.HookBridge.Phase.POST,
                dummyTool("bash"),
                "call-3",
                Map.of(),
                Tool.ToolResult.of("hi"),
                appState);
        assertThat(out.verdict()).isEqualTo(StreamingToolExecutor.HookBridge.Verdict.BLOCK);
    }

    @Test
    void prePhaseIgnoresPostHooks(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        HookRegistry reg = new HookRegistry();
        reg.register(new EditErrorRecoveryHook());  // POST_TOOL_USE
        StreamingToolExecutor.HookBridge bridge = Hooks.asPostBridge(reg);
        // A PRE phase invocation: the bridge returns continue_() without
        // invoking the registry. We assert by checking the verdict.
        StreamingToolExecutor.HookBridge.Outcome out = bridge.runWithOutcome(
                StreamingToolExecutor.HookBridge.Phase.PRE,
                dummyTool("file_edit"),
                "call-4",
                Map.of(),
                Tool.ToolResult.of("ok"),
                appState);
        assertThat(out.verdict()).isEqualTo(StreamingToolExecutor.HookBridge.Verdict.CONTINUE);
        assertThat(out.newResult()).isNull();
    }

    private static Tool dummyTool(String name) {
        return Tools.build(new ToolDef(name, "test", Map.of(),
                (in, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));
    }
}
