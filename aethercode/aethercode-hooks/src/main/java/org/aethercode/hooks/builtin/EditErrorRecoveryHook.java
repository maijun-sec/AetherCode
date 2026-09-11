package org.aethercode.hooks.builtin;

import org.aethercode.core.tool.Tool;
import org.aethercode.hooks.Hook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * edit-error recovery hint.
 *
 * <p>{@code FileEditTool} reports three common mistakes the model
 * makes when crafting an {@code old_string} / {@code new_string}
 * pair:
 * <ul>
 *   <li>{@code "old_string and new_string are identical"} — the
 *       model is "editing" to the same content (silent no-op),</li>
 *   <li>{@code "old_string not found in {path}"} — the model
 *       assumed the file has content it does not,</li>
 *   <li>{@code "old_string matches N places in {path}"} — the
 *       snippet is ambiguous and the model needs more context
 *       to disambiguate.</li>
 * </ul>
 *
 * <p>Without a hint, the model often loops: it sees the error,
 * tries the same string again, fails again, and only after a
 * couple of turns does it realise the file is not what it
 * thought. The hook intercepts the POST_TOOL_USE event and
 * appends a short, direct reminder to the tool result telling
 * the model to re-read the file before retrying.
 *
 * <p>prior round: the hook now returns
 * {@link Outcome.ContinueWithResult} so the bridge rebuilds
 * the result with the hint appended. The original
 * {@code isError} flag is preserved (the edit still failed —
 * the hint is guidance, not a fix). The FileEditTool itself
 * still bakes the same hint into the initial error string
 * (legacy belt-and-braces for non-hook callers like
 * {@code --print}), so the model sees the hint either way.
 */
public class EditErrorRecoveryHook implements Hook {

    private static final Logger LOG = LoggerFactory.getLogger(EditErrorRecoveryHook.class);

    public static final String FILE_EDIT = "file_edit";

    /** Error patterns emitted by {@code FileEditTool}. Lower-case
     *  substrings — we match case-insensitively. */
    public static final List<String> EDIT_ERROR_PATTERNS = Arrays.asList(
            "old_string and new_string are identical",
            "old_string not found",
            "old_string matches"
    );

    /** Reminder text appended to the tool result. The wording
     *  is intentionally short and direct: "READ the file
     *  immediately, VERIFY, then retry with the corrected
     *  content". */
    public static final String EDIT_ERROR_REMINDER = "\n\n[EDIT ERROR — IMMEDIATE ACTION REQUIRED]\n"
            + "You made an Edit mistake. STOP and do this NOW:\n"
            + "1. READ the file immediately to see its ACTUAL current state (use file_read)\n"
            + "2. VERIFY what the content really looks like — your assumption was wrong\n"
            + "3. APOLOGIZE briefly to the user for the error\n"
            + "4. CONTINUE with the corrected action based on the real file content\n"
            + "DO NOT attempt another edit until you have read the file.";

    @Override
    public Kind kind() { return Kind.POST_TOOL_USE; }

    @Override
    public CompletableFuture<Outcome> run(HookContext ctx) {
        String toolName = ctx.toolName() == null ? "" : ctx.toolName();
        if (!FILE_EDIT.equals(toolName)) {
            return CompletableFuture.completedFuture(new Outcome.Continue());
        }
        Tool.ToolResult result = ctx.result();
        if (result == null) {
            return CompletableFuture.completedFuture(new Outcome.Continue());
        }
        // Only act on error results. Successful edits don't
        // need a reminder.
        if (!result.isError()) {
            return CompletableFuture.completedFuture(new Outcome.Continue());
        }
        Object out = result.output();
        String body = out == null ? "" : out.toString();
        String lower = body.toLowerCase();
        for (String pattern : EDIT_ERROR_PATTERNS) {
            if (lower.contains(pattern.toLowerCase())) {
                // mutate the result. The bridge
                // (Hooks.asPostBridge) sees ContinueWithResult
                // and rebuilds the ToolResult with this
                // output, preserving the original isError +
                // attachments. The model now sees the hint
                // as part of the tool's feedback, exactly
                // where it's actionable.
                String newBody = body + EDIT_ERROR_REMINDER;
                LOG.info("对应历史 round edit-error mutation: pattern='{}' session={} bytes={}",
                        pattern, ctx.sessionId(), newBody.length());
                return CompletableFuture.completedFuture(
                        new Outcome.ContinueWithResult(newBody));
            }
        }
        return CompletableFuture.completedFuture(new Outcome.Continue());
    }
}
