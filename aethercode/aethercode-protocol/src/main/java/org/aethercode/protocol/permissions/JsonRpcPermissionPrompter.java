package org.aethercode.protocol.permissions;

import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.permission.ToolPermissionPrompter;
import org.aethercode.protocol.methods.AetherCodeMethods;
import org.aethercode.protocol.methods.AetherCodeMethods.PermissionDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * a {@link ToolPermissionPrompter} that bridges the in-process
 * permission ask to the JSON-RPC client.
 *
 * <p>When the engine's {@code ProjectPermissionPolicy} needs to ask
 * the user, it delegates to this prompter. We:
 * <ol>
 *   <li>Emit a {@code permission_request} notification carrying the
 *       tool name, the input, a reason, and a risk level.</li>
 *   <li>Return a {@link CompletableFuture} that resolves when the
 *       client calls {@code permissionResponse} with the matching
 *       {@code requestId}.</li>
 * </ol>
 *
 * <p>If the client never replies, the future times out (default
 * 60s) and we deny. This keeps a hung client from blocking the
 * daemon's worker thread.
 */
public class JsonRpcPermissionPrompter implements ToolPermissionPrompter {

    private static final Logger LOG = LoggerFactory.getLogger(JsonRpcPermissionPrompter.class);

    private final AetherCodeMethods methods;
    private final long timeoutMs;

    public JsonRpcPermissionPrompter(AetherCodeMethods methods) {
        // default timeout raised from 60s to 5min
        // (300_000ms). 60s was the binding constraint
        // for headless / scripted runs — a model mid-
        // tool-sequence with 5+ pending bash asks would
        // see the 60s timer fire and the engine would
        // deny the call, breaking the run. 5min leaves
        // room for a single tool sequence to complete
        // even with mid-sequence thinking pauses.
        // Interactive TUI runs are unaffected — they
        // respond in <1s in practice.
        this(methods, 300_000L);
    }

    public JsonRpcPermissionPrompter(AetherCodeMethods methods, long timeoutMs) {
        this.methods = methods;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public CompletableFuture<PermissionResult> ask(Tool tool, Map<String, Object> input, String question) {
        if (methods == null) {
            return CompletableFuture.completedFuture(
                    PermissionResult.Deny.of("no JSON-RPC connection"));
        }
        // classify risk so the TUI can render a danger icon.
        String riskLevel = classifyRisk(tool.name(), input);
        // short-circuit low-risk tool calls. The
        // flag is read on every ask (volatile), so a
        // flip via setAutoApproveLowRisk takes effect on
        // the very next tool call. Returning a completed
        // future with Allow + incrementing the
        // cumulative counter is the right primitive —        // the engine's ProjectPermissionPolicy treats
        // a completed Allow future the same as a
        // user-clicked Allow. The notification carries
        // the per-event detail for the desktop's
        // StatusBar badge.
        if ("low".equals(riskLevel) && methods.isAutoApproveLowRisk()) {
            methods.recordAutoApproved(tool.name(), input, question, riskLevel);
            LOG.debug("R120 auto-approved low-risk tool call: {}", tool.name());
            return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
        }
        // prior round: opt-in short-circuit for HIGH risk only.
        // The R126 path auto-approved medium + high; R130 narrows
        // to high-only because the user asked for "medium risk
        // — explicit confirm". Medium is now always asked (with
        // a configurable timeout, see below), so an unattended
        // headless driver still gets a confirmation prompt for
        // the things humans care about (writes, edits, npm
        // install) while the boilerplate (high-risk reads of
        // internal state) auto-approves. Driven by
        // AETHERCODE_AUTO_APPROVE_ALL=1 at startup or the
        // renderer's setAutoApproveMediumHigh RPC toggle.
        // Critical risk is NEVER auto-approved (rm -rf, sudo,
        // mkfs, dd — still asks). The autoApprovedElevatedCount
        // counter is split (low vs elevated) so a StatusBar
        // badge can colour-code the two.
        // re-expand auto-approve to cover BOTH medium and
        // high risk. The R130 narrowing (high-only) meant
        // file_write and file_edit ALWAYS required manual
        // confirmation, even when the user had explicitly toggled
        // the "auto-allow" switch in the StatusBar. End-to-end
        // test (probe-test.py, 2026-09-01) showed:
        //   setAutoApproveMediumHigh(true) returns ok
        //   file_write still asks permission (5min timeout)
        //   model gets "DO NOT retry" but retries anyway
        //   file never gets written
        // The user was rightly frustrated. R183 restores the
        // R126 behaviour for the auto-allow flag: a single toggle
        // covers medium + high (but critical risk is NEVER
        // auto-approved; rm -rf / sudo / mkfs / dd still ask).
        if (("medium".equals(riskLevel) || "high".equals(riskLevel))
                && methods.isAutoApproveMediumHigh()
                && !methods.isAskMode()) {
            // R277 (2026-09-16): the previous round unconditionally
            // short-circuited medium/high when autoApproveMediumHigh
            // was true. The user picked "主动询问" (= ASK_BEFORE_TOOL /
            // DEFAULT / PLAN) from the dropdown but every tool call
            // still went through without confirmation — the flag had
            // silently overridden the mode. The fix: respect the
            // explicit-ask mode as the source of truth. The flag
            // still wins in BYPASS_PERMISSIONS / ACCEPT_EDITS so the
            // batch-workflow safety net (R268d) stays intact.
            methods.recordAutoApproved(tool.name(), input, question, riskLevel);
            LOG.debug("R277 auto-approved {} tool call (mode={}, flag=true)", riskLevel, methods.currentPermissionModeName());
            return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
        }
        CompletableFuture<PermissionDecision> fut = methods.askPermission(
                "n/a", // runId is not threaded into the prompter API yet; TUI can correlate via tool+input
                tool.name(),
                input,
                question,
                riskLevel);
        return fut
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    // the previous version built the reason as
                    // `"timeout: " + ex.getMessage()`, but Java's
                    // TimeoutException#getMessage() returns null, so
                    // the model received `content: "timeout: null"` —
                    // a useless string the model couldn't act on. The
                    // model then re-emitted the same tool call, which
                    // timed out again, and the engine looped forever
                    // (loopStops=0, errorRate=0.89, turnsCompleted=0).
                    //
                    // Now we surface: (a) the actual timeout
                    // duration, (b) a clear "STOP retrying" hint that
                    // names the tool, and (c) the underlying cause when
                    // it's a real failure (not a timeout).
                    String reason;
                    if (ex instanceof java.util.concurrent.TimeoutException) {
                        reason = "permission ask for '" + tool.name()
                                + "' timed out after " + (timeoutMs / 1000) + "s with no user response. "
                                + "DO NOT retry this tool call — the user is not at the keyboard. "
                                + "Either: (1) tell the user what you wanted to do and let them re-run, "
                                + "or (2) pick a different tool / approach that does not require permission.";
                    } else {
                        reason = "permission ask for '" + tool.name()
                                + "' failed: " + ex.getClass().getSimpleName()
                                + (ex.getMessage() == null ? "" : ": " + ex.getMessage())
                                + ". STOP retrying and surface the issue to the user.";
                    }
                    LOG.warn("permission ask for {} timed out / failed: {} -> {}",
                            tool.name(), ex.getMessage(), reason);
                    return new PermissionDecision(AetherCodeMethods.DECISION_DENY, reason);
                })
                .thenApply(decision -> {
                    if (decision.isAllow()) {
                        return new PermissionResult.Allow(input);
                    }
                    return PermissionResult.Deny.of(decision.reason());
                });
    }

    /**
     * Classify the risk of a tool call. Heuristic: anything that
     * touches {@code bash}, {@code file_write}, {@code file_edit},
     * {@code file_delete}, or has {@code sudo} / {@code rm -rf} in
     * its args is "high". {@code file_read} / {@code grep} / {@code glob}
     * are "low". Everything else is "medium".
     */
    static String classifyRisk(String toolName, Map<String, Object> input) {
        if (toolName == null) return "medium";
        String n = toolName.toLowerCase(java.util.Locale.ROOT);
        if (n.equals("bash") || n.equals("shell") || n.equals("exec") || n.equals("run_command")) {
            String cmd = String.valueOf(input.getOrDefault("command", ""));
            if (cmd.contains("rm -rf") || cmd.contains("sudo ") || cmd.contains("mkfs")
                    || cmd.contains("dd ") || cmd.contains(":(){:|:&};:")) {
                return "critical";
            }
            return "high";
        }
        if (n.contains("delete") || n.contains("drop") || n.contains("truncate")) return "high";
        if (n.contains("write") || n.contains("edit") || n.contains("create") || n.contains("modify")) {
            return "medium";
        }
        if (n.contains("read") || n.contains("list") || n.contains("glob") || n.contains("grep")
                || n.contains("search") || n.contains("stat") || n.contains("get")) {
            return "low";
        }
        return "medium";
    }
}