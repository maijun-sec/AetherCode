package org.aethercode.permission;

import org.aethercode.core.engine.PermissionPolicy;
import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Permission policy that combines project / user settings (allow/deny/ask rules) with the
 * session-wide {@link PermissionMode}. Wire it as the engine's policy and you get the
 * canonical Claude Code decision flow.
 */
public class ProjectPermissionPolicy implements PermissionPolicy {

    private final SettingsPermissions rules;
    private final PermissionMode mode;
    /** volatile so a daemon can swap the prompter after
     *  construction (the JSON-RPC daemon replaces the JLine
     *  prompter with a JsonRpcPermissionPrompter once the
     *  dispatcher is wired). */
    private volatile ToolPermissionPrompter prompter; // may be null -> ask is auto-denied

    public ProjectPermissionPolicy(SettingsPermissions rules, PermissionMode mode, ToolPermissionPrompter prompter) {
        this.rules = rules == null ? SettingsPermissions.empty() : rules;
        this.mode = mode == null ? PermissionMode.DEFAULT : mode;
        this.prompter = prompter;
    }

    /** package-private accessor for the live rules (used by MatrixPermissionPolicy.withMatrix). */
    SettingsPermissions rules() { return rules; }

    /** replace the prompter (e.g. swap a JLine prompter for
     *  a JSON-RPC one when the daemon starts). */
    public void setPrompter(ToolPermissionPrompter p) { this.prompter = p; }
    public ToolPermissionPrompter prompter() { return prompter; }
    /** expose the live permission mode so callers rebuilding
     *  the policy (e.g. {@code AetherCodeMethods.permissionPolicyOverride})
     *  can keep the same mode while swapping the rules. */
    public PermissionMode mode() { return mode; }

    /** return a copy of this policy with the mode replaced.
     *  Used by {@code AetherCodeMethods.setPermissionMode} so the
     *  runtime mode change actually reaches the live policy — before
     *  this, the RPC only updated {@code AppState.permissionMode} and
     *  the policy kept the mode it was constructed with, so a user
     *  switching to ACCEPT_TASK or BYPASS_PERMISSIONS still saw
     *  every tool call prompt because the policy was still DEFAULT.
     *  The new instance shares the same {@code prompter} reference
     *  (still the same JSON-RPC bridge) and the same {@code rules}
     *  object, so no I/O happens during the swap. The volatile
     *  sub-task id is also copied so an in-flight sub-task
     *  boundary is preserved across the swap. */
    public ProjectPermissionPolicy withMode(PermissionMode newMode) {
        ProjectPermissionPolicy p = new ProjectPermissionPolicy(
                this.rules, newMode == null ? PermissionMode.DEFAULT : newMode, this.prompter);
        if (this.currentSubTaskId != null) {
            p.currentSubTaskId = new java.util.concurrent.atomic.AtomicReference<>(this.currentSubTaskId.get());
        }
        return p;
    }

    /** install the live sub-task id used by ACCEPT_TASK. */
    @Override
    public void setCurrentSubTaskId(String subTaskId) {
        if (this.currentSubTaskId == null) {
            synchronized (this) {
                if (this.currentSubTaskId == null) {
                    this.currentSubTaskId = new java.util.concurrent.atomic.AtomicReference<>();
                }
            }
        }
        this.currentSubTaskId.set(subTaskId);
    }

    /** read the live sub-task id (for tests + the orchestrator
     *  deciding whether to fire a boundary notification). */
    public String currentSubTaskId() {
        return currentSubTaskId == null ? null : currentSubTaskId.get();
    }

    public CompletableFuture<PermissionResult> check(Tool tool, Map<String, Object> input, Tool.CallContext ctx) {
        String prompt = extractPrompt(tool, input);

        // 1. deny rules
        for (Rule r : rules.deny) {
            if (r.matchesTool(tool.name()) && r.matchesPrompt(prompt)) {
                String msg = r.reason() != null ? r.reason() : "denied by rule for " + tool.name();
                return CompletableFuture.completedFuture(PermissionResult.Deny.of(msg));
            }
        }
        // 2. ask rules
        for (Rule r : rules.ask) {
            if (r.matchesTool(tool.name()) && r.matchesPrompt(prompt)) {
                return resolveAsk(tool, input, "ask rule: " + tool.name());
            }
        }
        // 3. allow rules
        for (Rule r : rules.allow) {
            if (r.matchesTool(tool.name()) && r.matchesPrompt(prompt)) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
        }
        // 4. fall back to mode
        return switch (mode) {
            case BYPASS_PERMISSIONS, AUTO_READ_ONLY ->
                    CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            // ACCEPT_EDITS is the "smart permission" mode
            // (UI label "智能授权"). It auto-allows read-only
            // tools and bash read-only commands, and asks
            // for anything that mutates state. The split
            // mirrors the user's three-tier mental model:
            //   - Read tools (file_read, glob, grep, list,
            //     web_search) → Allow
            //   - Bash read-only commands (ls, cat, pwd,
            //     find, head, tail, grep, wc, stat, df,
            //     du, tree, etc. and no `>` / `|` / `&&`
            //     / `rm` / `mv`) → Allow
            //   - Mutating bash, file_write, file_edit,
            //     web write → Ask
            //   - Critical (rm -rf, sudo, mkfs) → Ask
            // legacy, ACCEPT_EDITS auto-allowed every tool,
            // which defeated the mode. Now the user can
            // switch to ACCEPT_EDITS and stop being asked
            // for `ls` and `cat`.
            case ACCEPT_EDITS -> resolveSmart(tool, input);
            // ASK_BEFORE_TOOL is the explicit "interrupt me before
            // every non-read-only tool call" mode. It routes through the
            // same resolveAsk path as DEFAULT / PLAN but the name is
            // discoverable in the TUI's status bar (the user can read
            // "ASK_BEFORE_TOOL" and immediately know they will be
            // prompted) and is the value the suggester now recommends
            // for empty / exploration projects (was DEFAULT).
            case DEFAULT, ASK_BEFORE_TOOL, PLAN -> resolveAsk(tool, input, tool.name() + " requires approval");
            // ACCEPT_TASK auto-allows everything within the
            // current sub-task. The orchestrator tags every call
            // with the current sub-task id (in ctx.extras); if the
            // call's id matches the active one, we auto-allow.
            // When a new sub-task starts the engine pushes a fresh
            // "task boundary" notification and the user gets ONE
            // decision prompt for the next tool call in that new
            // sub-task.
            case ACCEPT_TASK -> resolveAcceptTask(tool, input, ctx);
        };
    }

    /**
     * ACCEPT_TASK resolution. Auto-allow if the call belongs
     * to the currently-active sub-task (the engine sets the id
     * via {@code ctx.setExtra("subTaskId", id)} right before the
     * call). Otherwise, surface a single ask so the user can
     * either allow the new sub-task or deny it.
     *
     * <p>If the engine doesn't tag the call with a sub-task id
     * (e.g. legacy callers or test stubs), we fall back to
     * auto-allow — this matches the spirit of "ACCEPT_TASK" and
     * avoids spurious prompts.
     */
    /**
     * the "smart permission" policy (the UI label
     * is "智能授权"). Auto-allow read-only tools + bash
     * read-only commands; ask for anything that mutates.
     * See the doc on the {@code ACCEPT_EDITS} arm in
     * {@link #check(Tool, Map, Tool.CallContext)} for the
     * full mental model.
     */
    private CompletableFuture<PermissionResult> resolveSmart(Tool tool, Map<String, Object> input) {
        // The Tool interface's isReadOnly(input) is the
        // canonical signal. BashTool overrides it to
        // classify the command against a documented
        // read-only whitelist (ls, cat, pwd, find, …) and
        // refuse any shell metachar / destructive token
        // (>, |, &&, rm, mv, …). Other read-only tools
        // (file_read, glob, grep, web_search, …) override
        // isReadOnly to return true directly. Anything
        // else falls through to the ask path.
        //
        // We don't need to know the tool's specific name
        // or import the bash classifier — the Tool
        // interface's default method routes to the right
        // implementation per tool. This also avoids a
        // circular dependency between aethercode-permission
        // and aethercode-tools.
        if (tool.isReadOnly(input)) {
            return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
        }
        // Everything else (file_write, file_edit,
        // mutating bash, web write, etc.) — ask.
        return resolveAsk(tool, input, tool.name() + " requires approval (智能授权模式)");
    }

    private CompletableFuture<PermissionResult> resolveAcceptTask(
            Tool tool, Map<String, Object> input, Tool.CallContext ctx) {
        Object ctxTaskId = ctx == null ? null : ctx.extra("subTaskId");
        // No sub-task tag = treat as "in the current task" = auto-allow.
        if (ctxTaskId == null) {
            return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
        }
        Object currentTaskId = currentSubTaskId == null ? null : currentSubTaskId.get();
        if (currentTaskId != null && currentTaskId.equals(ctxTaskId)) {
            // Same sub-task as before — auto-allow, the user has
            // already approved this scope.
            return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
        }
        // Different sub-task (or the first call in a new one).
        // Ask the user. If they allow, the engine updates
        // currentSubTaskId to the new value via setCurrentSubTaskId.
        return resolveAsk(tool, input, "new sub-task boundary: " + tool.name() + " requires approval");
    }

    /**
     * the live "current sub-task id" used by ACCEPT_TASK.
     * Set by the engine right after a SubTaskStart event fires.
     * The next permission check that comes in with a DIFFERENT
     * sub-task id in its ctx.extras will be promoted to an ask.
     *
     * <p>Volatile + AtomicReference because the orchestrator
     * thread may update it between permission checks on the same
     * tool call (race on rapid task transitions is harmless —
     * we'll just ask one extra time at worst).
     */
    private volatile java.util.concurrent.atomic.AtomicReference<String> currentSubTaskId;

    private CompletableFuture<PermissionResult> resolveAsk(Tool tool, Map<String, Object> input, String question) {
        if (prompter == null || tool.isReadOnly(input)) {
            // Read-only tools never need a prompt — auto-allow.
            return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
        }
        return prompter.ask(tool, input, question);
    }

    private static String extractPrompt(Tool tool, Map<String, Object> input) {
        // Tool-specific prompt extraction. Most tools name the relevant field the same way the
        // TS source does: bash -> "command"; file_* -> "file_path".
        String name = tool.name();
        if (name.equalsIgnoreCase("bash") || name.equalsIgnoreCase("shell")) {
            return str(input.get("command"));
        }
        if (name.equalsIgnoreCase("file_read")
                || name.equalsIgnoreCase("file_edit")
                || name.equalsIgnoreCase("file_write")) {
            return str(input.get("file_path"));
        }
        if (name.equalsIgnoreCase("web_fetch")) {
            return str(input.get("url"));
        }
        return null;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
