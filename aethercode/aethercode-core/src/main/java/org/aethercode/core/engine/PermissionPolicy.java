package org.aethercode.core.engine;

import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The permission policy that gates every tool invocation. Implementations are expected to be
 * fast — they may consult session settings, ask the user, or defer to a project rule. They must
 * never run the tool themselves; they only decide whether the call is allowed.
 *
 * <p>The same policy is consulted for both the synchronous check (in
 * {@link org.aethercode.core.tool.Tool#checkPermissions}) and the orchestrator's runtime gate.
 * If the tool returns {@code Allow} but the policy says {@code Deny}, the orchestrator wins.
 */
public interface PermissionPolicy {

    CompletableFuture<PermissionResult> check(
            Tool tool,
            Map<String, Object> input,
            Tool.CallContext ctx
    );

    /**
     * notify the policy that the engine has transitioned into
     * a new sub-task. The policy can use this to decide whether
     * the next tool call should be auto-allowed (ACCEPT_TASK mode)
     * or promoted to an ask (mode where every sub-task boundary
     * surfaces one decision prompt to the user).
     *
     * <p>The default implementation is a no-op so existing
     * implementations don't break. {@code ProjectPermissionPolicy}
     * overrides this to track the live sub-task id and compare it
     * against the call's subTaskId extras during the next check.
     *
     * @param subTaskId the active sub-task id formatted as
     *                  {@code "taskIdx:subTaskId"}, or {@code null}
     *                  when no sub-task is in flight (e.g. before
     *                  the model emits its first todo list).
     */
    default void setCurrentSubTaskId(String subTaskId) {
        // no-op
    }

    /** Convenience: allow-without-asking. Used in tests and for safe-by-default tools. */
    static PermissionPolicy allowAll() {
        return new PermissionPolicy() {
            @Override
            public CompletableFuture<PermissionResult> check(
                    Tool tool, Map<String, Object> input, Tool.CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
        };
    }

    /** Convenience: deny everything. */
    static PermissionPolicy denyAll() {
        return new PermissionPolicy() {
            @Override
            public CompletableFuture<PermissionResult> check(
                    Tool tool, Map<String, Object> input, Tool.CallContext ctx) {
                return CompletableFuture.completedFuture(
                        PermissionResult.Deny.of("all tool calls denied by policy"));
            }
        };
    }
}
