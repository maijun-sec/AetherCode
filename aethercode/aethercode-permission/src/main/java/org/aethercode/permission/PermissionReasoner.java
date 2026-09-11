package org.aethercode.permission;

import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * explain why a tool call would be allowed, denied, or asked. Renders the
 * same decision flow as {@link ProjectPermissionPolicy} but returns a human-readable
 * reason string alongside the verdict, so users can see which rule or mode
 * fired. Pure logic, no I/O — does not call the prompter.
 *
 * <p>Modelled on the TS {@code src/permissions/permissionReasoner.ts}.
 */
public final class PermissionReasoner {

    public enum Verdict { ALLOW, DENY, ASK }

    public record Reason(Verdict verdict, String source, String explanation) {
        /** @return true when the call would proceed without an interactive prompt. */
        public boolean proceeds() { return verdict != Verdict.ASK; }
    }

    private final SettingsPermissions rules;
    private final PermissionMode mode;

    public PermissionReasoner(SettingsPermissions rules, PermissionMode mode) {
        this.rules = rules == null ? SettingsPermissions.empty() : rules;
        this.mode = mode == null ? PermissionMode.DEFAULT : mode;
    }

    /**
     * Evaluate the tool call against the rules + mode. The returned reason always
     * carries a non-null {@code explanation}; the source is one of:
     * {@code "deny-rule:<tool>"}, {@code "ask-rule:<tool>"}, {@code "allow-rule:<tool>"},
     * {@code "mode:<mode>"}, {@code "read-only-auto"}, or {@code "no-prompter-auto"}.
     */
    public Reason evaluate(Tool tool, Map<String, Object> input) {
        String prompt = extractPrompt(tool, input);

        // 1. deny rules
        Rule matchedDeny = firstMatch(rules.deny, tool.name(), prompt);
        if (matchedDeny != null) {
            String reason = matchedDeny.reason() != null ? matchedDeny.reason() : "rule on " + tool.name();
            return new Reason(Verdict.DENY, "deny-rule:" + tool.name(),
                    "denied by rule — " + reason);
        }

        // 2. ask rules
        Rule matchedAsk = firstMatch(rules.ask, tool.name(), prompt);
        if (matchedAsk != null) {
            return new Reason(Verdict.ASK, "ask-rule:" + tool.name(),
                    "ask rule — user must confirm before this runs");
        }

        // 3. allow rules
        Rule matchedAllow = firstMatch(rules.allow, tool.name(), prompt);
        if (matchedAllow != null) {
            return new Reason(Verdict.ALLOW, "allow-rule:" + tool.name(),
                    "allowed by rule for " + tool.name());
        }

        // 4. mode
        return switch (mode) {
            case BYPASS_PERMISSIONS, ACCEPT_EDITS ->
                    new Reason(Verdict.ALLOW, "mode:" + mode.name().toLowerCase(),
                            "mode " + mode + " auto-allows " + tool.name());
            case AUTO_READ_ONLY -> {
                if (tool.isReadOnly(input)) {
                    yield new Reason(Verdict.ALLOW, "read-only-auto",
                            "auto-read-only mode; " + tool.name() + " is read-only");
                }
                yield new Reason(Verdict.ASK, "mode:" + mode.name().toLowerCase(),
                        "auto-read-only mode blocks writes from " + tool.name());
            }
            case DEFAULT, ASK_BEFORE_TOOL, PLAN -> new Reason(Verdict.ASK, "mode:" + mode.name().toLowerCase(),
                    "default/ask-before-tool/plan mode requires user confirmation for " + tool.name());
            // ACCEPT_TASK auto-allows calls in the current
            // sub-task. Reasoner is best-effort: the policy's
            // own resolveAcceptTask makes the runtime call; we
            // mirror its verdict here for audit logs.
            case ACCEPT_TASK -> new Reason(Verdict.ASK, "mode:" + mode.name().toLowerCase(),
                    "accept-task mode; the policy will auto-allow calls in the current sub-task");
        };
    }

    private static Rule firstMatch(java.util.List<Rule> list, String tool, String prompt) {
        for (Rule r : list) {
            if (r.matchesTool(tool) && r.matchesPrompt(prompt)) return r;
        }
        return null;
    }

    private static String extractPrompt(Tool tool, Map<String, Object> input) {
        if (tool == null || input == null) return null;
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
