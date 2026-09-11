package org.aethercode.core.engine;

import org.aethercode.core.app.AppState;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contract test for the live-policy-swap
 * contract between {@code AetherCodeEngine} and
 * {@code StreamingToolExecutor}.
 *
 * <p>legacy the executor captured its
 * {@code PermissionPolicy} reference at construction
 * time and never saw later {@code setPermissionMode}
 * or {@code swapPolicy} calls. The engine updated
 * {@code this.policy} to a fresh
 * {@code ProjectPermissionPolicy} with the new mode,
 * but the executor's {@code this.policy} field
 * stayed final-and-stale. The user picked 始终授权
 * (BYPASS_PERMISSIONS) in the dropdown, the
 * status bar showed it, the engine's appState
 * reflected it, yet tool calls still prompted
 * because the streaming path consulted the
 * pre-swap policy.
 *
 * <p>R206 makes the executor's policy field
 * {@code volatile} + mutable and adds a
 * {@code setPolicy} method. {@code AetherCodeEngine}
 * re-pushes the freshly-built policy on every
 * {@code setPermissionMode} / {@code swapPolicy}
 * call. The four cases below pin the contract.
 *
 * <p>The test uses local anonymous policy
 * implementations (not {@code ProjectPermissionPolicy}
 * which lives in aethercode-permission and would
 * cycle) but the contract is the same: the
 * executor must consult whatever
 * {@code policy()} returns at the moment of the
 * tool call, not the reference captured at
 * construction.
 */
class StreamingToolExecutorR206PolicySwapTest {

    private static Tool stubTool(String name) {
        return Tools.build(new ToolDef(name, name, Map.of(),
                (in, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));
    }

    private static AppState appState(Tool... tools) {
        AppState st = new AppState("test", Path.of(""));
        for (Tool t : tools) st.toolPool().add(t);
        return st;
    }

    /** Minimal policy that tracks the live
     *  sub-task id (mirrors the prior round default-method
     *  on PermissionPolicy) and lets each test
     *  install a custom check behaviour. */
    private static final class TrackingPolicy implements PermissionPolicy {
        final java.util.concurrent.atomic.AtomicInteger checkCalls =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicReference<String> lastSubTaskId =
                new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.function.Function<Tool, PermissionResult> behaviour;
        TrackingPolicy(java.util.function.Function<Tool, PermissionResult> behaviour) {
            this.behaviour = behaviour;
        }
        @Override
        public CompletableFuture<PermissionResult> check(
                Tool tool, Map<String, Object> input, Tool.CallContext ctx) {
            checkCalls.incrementAndGet();
            return CompletableFuture.completedFuture(behaviour.apply(tool));
        }
        @Override
        public void setCurrentSubTaskId(String subTaskId) {
            lastSubTaskId.set(subTaskId);
        }
    }

    @Test
    void setPolicy_swapsTheLiveReference() {
        // Pin the field is mutable. legacy it was
        // `final` and `setPolicy` didn't exist; a
        // refactor that re-adds `final` would
        // silently bring the bypass-still-prompts
        // bug back.
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 4);
        PermissionPolicy newPolicy = PermissionPolicy.allowAll();
        exec.setPolicy(newPolicy);
        assertThat(exec.policy()).isSameAs(newPolicy);
    }

    @Test
    void execute_afterSetPolicy_consultsTheNewPolicy() {
        // The executor must observe the swap on
        // the very next tool call. Volatile
        // happens-before guarantees this.
        AppState st = appState(stubTool("ok"));

        // Build a "prompts" policy (the original
        // mode). It records every check() call.
        TrackingPolicy askPolicy = new TrackingPolicy(
                tool -> {
                    throw new RuntimeException("pre-swap policy must NOT be consulted");
                });

        StreamingToolExecutor exec = new StreamingToolExecutor(askPolicy, 4);

        // Simulate a mid-run swap to a fresh
        // "bypass" policy. The executor must
        // consult the new reference on the very
        // next tool call (not the cached one).
        TrackingPolicy bypassPolicy = new TrackingPolicy(
                tool -> new PermissionResult.Allow(Map.of()));
        exec.setPolicy(bypassPolicy);

        var events = exec.run(
                List.of(new ContentBlock.ToolUseBlock("1", "ok", Map.of())),
                st);
        var results = events.collect(java.util.stream.Collectors.toList());
        assertThat(results).isNotEmpty();
        assertThat(askPolicy.checkCalls.get())
                .as("pre-swap policy must NOT be consulted after setPolicy")
                .isEqualTo(0);
        assertThat(bypassPolicy.checkCalls.get())
                .as("post-swap policy IS consulted")
                .isEqualTo(1);
    }

    @Test
    void setPolicy_pushedToExecutor() {
        // R206 contract: when AetherCodeEngine
        // builds a new policy and calls
        // executor.setPolicy(newPolicy), the
        // executor's policy() reads the new
        // instance. This is the path the engine
        // uses in both setPermissionMode and
        // swapPolicy.
        TrackingPolicy initial = new TrackingPolicy(
                tool -> new PermissionResult.Allow(Map.of()));
        StreamingToolExecutor exec = new StreamingToolExecutor(initial, 4);
        assertThat(exec.policy()).isSameAs(initial);

        // Simulate the engine's setPermissionMode
        // building a new policy and pushing it
        // down. (The real engine has the swapPolicy
        // field; here we just verify the executor
        // observes the push.)
        TrackingPolicy swapped = new TrackingPolicy(
                tool -> new PermissionResult.Allow(Map.of()));
        exec.setPolicy(swapped);
        assertThat(exec.policy()).isSameAs(swapped);
    }

    @Test
    void setPolicy_preservesSubTaskBoundary() {
        // when the engine swaps the policy
        // mid-run (e.g. the user picks ACCEPT_TASK
        // after a different mode), the executor's
        // current sub-task id is re-pushed to the
        // new policy so the R86 boundary tracking
        // doesn't reset to "no active sub-task"
        // (which would have caused every subsequent
        // tool call in the same sub-task to
        // re-prompt).
        TrackingPolicy p1 = new TrackingPolicy(
                tool -> new PermissionResult.Allow(Map.of()));
        StreamingToolExecutor exec = new StreamingToolExecutor(p1, 4);
        exec.setCurrentSubTaskId("0:write_file");

        // Swap to a new policy (simulating a
        // cross-mode change).
        TrackingPolicy p2 = new TrackingPolicy(
                tool -> new PermissionResult.Allow(Map.of()));
        exec.setPolicy(p2);

        // The new policy's setCurrentSubTaskId was
        // called with the executor's existing
        // subTaskId. Read it back via the
        // policy's lastSubTaskId accessor.
        assertThat(p2.lastSubTaskId.get())
                .as("setPolicy must re-push current sub-task id to the new policy")
                .isEqualTo("0:write_file");
    }

    @Test
    void setPolicy_nullClearsTheReference() {
        // Defensive: setPolicy(null) is allowed
        // (e.g. when an external lifecycle resets
        // the policy to "no policy" before
        // installing a new one). The executor
        // must accept it and report null.
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 4);
        exec.setPolicy(null);
        assertThat(exec.policy()).isNull();
    }
}
