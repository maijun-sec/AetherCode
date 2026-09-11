package org.aethercode.hooks;

import org.aethercode.core.app.AppState;
import org.aethercode.core.engine.StreamingToolExecutor;
import org.aethercode.core.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * Adapter that turns the {@link HookRegistry} into a pair of {@link StreamingToolExecutor.HookBridge}s.
 * The registry is consulted sequentially per kind; the first {@code Block} verdict
 * short-circuits the tool call.
 */
public final class Hooks {

    private Hooks() {}

    public static StreamingToolExecutor.HookBridge asPreBridge(HookRegistry registry) {
        return (phase, tool, id, input, result, appState) -> {
            if (phase != StreamingToolExecutor.HookBridge.Phase.PRE) return true;
            Hook.Outcome o = registry.runAll(Hook.Kind.PRE_TOOL_USE,
                    Hook.HookContext.forPre(appState.sessionId(), tool.name(), input)).join();
            return o instanceof Hook.Outcome.Continue;
        };
    }

    public static StreamingToolExecutor.HookBridge asPostBridge(HookRegistry registry) {
        // use the new runWithOutcome seam so a hook
        // can mutate the tool's result. The default `run`
        // (boolean) method delegates to runWithOutcome, so
        // bridges that only override `run` still work.
        return new StreamingToolExecutor.HookBridge() {
            @Override
            public boolean run(StreamingToolExecutor.HookBridge.Phase phase,
                               org.aethercode.core.tool.Tool tool, String id,
                               Map<String, Object> input,
                               Tool.ToolResult result,
                               org.aethercode.core.app.AppState appState) {
                // Delegates to runWithOutcome and translates
                // back to the boolean contract. Used only by
                // legacy callers; the executor itself calls
                // runWithOutcome below.
                StreamingToolExecutor.HookBridge.Outcome o = runWithOutcome(phase, tool, id, input, result, appState);
                if (o == null) return true;
                if (o.verdict() == StreamingToolExecutor.HookBridge.Verdict.BLOCK) return false;
                return true;
            }

            @Override
            public StreamingToolExecutor.HookBridge.Outcome runWithOutcome(
                    StreamingToolExecutor.HookBridge.Phase phase,
                    org.aethercode.core.tool.Tool tool, String id,
                    Map<String, Object> input,
                    Tool.ToolResult result,
                    org.aethercode.core.app.AppState appState) {
                if (phase != StreamingToolExecutor.HookBridge.Phase.POST) {
                    return StreamingToolExecutor.HookBridge.Outcome.continue_();
                }
                Hook.Outcome o = registry.runAll(Hook.Kind.POST_TOOL_USE,
                        Hook.HookContext.forPost(appState.sessionId(), tool.name(), input, result)).join();
                if (o instanceof Hook.Outcome.Continue) {
                    return StreamingToolExecutor.HookBridge.Outcome.continue_();
                }
                if (o instanceof Hook.Outcome.ContinueWithResult cw) {
                    // each field is independently nullable.
                    // null means "keep the original". A hook that
                    // wants to mutate just isError (e.g. escalate a
                    // silent failure) does not need to also resend
                    // the text body. The "empty string means no
                    // mutation" rule from prior round is preserved — a
                    // hook that returns newOutput = "" is treated
                    // as "no body change", matching the single-arg
                    // call sites in EditErrorRecoveryHook and
                    // friends. A hook that wants to clear the body
                    // should use markError() (escalate) or
                    // markSuccess() with explicit text, not a raw
                    // empty string.
                    boolean isOutputMutated      = cw.newOutput() != null && !cw.newOutput().isEmpty();
                    boolean isIsErrorMutated     = cw.newIsError() != null;
                    boolean isAttachmentsMutated = cw.newAttachments() != null;
                    if (!isOutputMutated && !isIsErrorMutated && !isAttachmentsMutated) {
                        // Nothing to do — fall back to Continue so
                        // the executor keeps the original result
                        // untouched. This is the same fast path
                        // that the prior round implementation took when
                        // newOutput was null or empty.
                        return StreamingToolExecutor.HookBridge.Outcome.continue_();
                    }
                    Object nextOut = isOutputMutated ? cw.newOutput() : result.output();
                    List<Tool.Attachment> nextAttachments = isAttachmentsMutated
                            ? cw.newAttachments()
                            : result.attachments();
                    boolean nextIsError = isIsErrorMutated ? cw.newIsError() : result.isError();
                    Tool.ToolResult replacement = new Tool.ToolResult(
                            nextOut == null ? "" : nextOut,
                            nextAttachments == null ? List.of() : nextAttachments,
                            nextIsError);
                    return StreamingToolExecutor.HookBridge.Outcome.replace(replacement);
                }
                if (o instanceof Hook.Outcome.Block) {
                    return StreamingToolExecutor.HookBridge.Outcome.block();
                }
                return StreamingToolExecutor.HookBridge.Outcome.continue_();
            }
        };
    }
}
