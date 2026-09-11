package org.aethercode.hooks;

import org.aethercode.core.message.Message;
import org.aethercode.core.tool.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Lifecycle hook. Mirrors the TS hook system but for prior round we ship only the most common ones:
 * {@code PreToolUse} and {@code PostToolUse}.
 *
 * <p>R89: extended the Kind enum with {@code SESSION_IDLE} (fired after the
 * model emits {@code run_end} but only when the engine believes the
 * session is sitting idle, e.g. no follow-up tool calls are in flight
 * and the run finished with a normal end-of-turn). This is the seam
 * {@code TodoContinuationHook} (and any future "boulder" hooks) hook
 * into to keep a long-running task rolling without forcing the user
 * to type "Continue" every time the model yields. See
 * {@code aethercode-hooks/src/main/java/org/aethercode/hooks/builtin/TodoContinuationHook.java}
 * for the reference implementation.
 */
public interface Hook {

    enum Kind {
        PRE_TOOL_USE,
        POST_TOOL_USE,
        USER_PROMPT_SUBMIT,
        STOP,
        // fired by AetherCodeEngine after every StreamEvent.RunEnd
        // when the session is otherwise idle (no in-flight tool calls,
        // no recovery-in-progress). Hooks that want to do "boulder
        // continuation", session-summarisation, or background-task
        // reaping subscribe to this kind. The {@code HookContext}
        // exposes the sessionId, the stop reason ("end_turn" /
        // "end_turn_and_tool" / "tool_calls" / "stop" / "max_tokens"
        // / "loop_detected" / "max_iterations" / "error" / etc.),
        // and the remaining {@code todoList} snapshot so a
        // continuation hook can decide without re-querying the
        // engine.
        SESSION_IDLE,
        // fired by the engine just before the chat
        // client is invoked (i.e. once per model call).
        // Hooks that want to inspect / rewrite the message
        // list, gate a high-cost call, or add cross-cutting
        // context (e.g. inject a "session memory digest"
        // prepended to the system prompt at call time)
        // subscribe to this kind. The {@code HookContext}
        // exposes the sessionId, a snapshot of the message
        // list (immutable view — the hook can build a
        // modified copy but the engine keeps the original),
        // the model id, and the iteration index (turn
        // number within the current query).
        PRE_MODEL_QUERY,
        // fired by the engine after every model
        // response is fully consumed (i.e. after the
        // chat client has returned a ChatResponse but
        // before tool calls are dispatched). Hooks that
        // want to log / transform / annotate the model's
        // output before it lands in the transcript or
        // before the next tool call picks it up
        // subscribe to this kind. The {@code HookContext}
        // exposes the sessionId, the model's text
        // (the full assistant message text, not just the
        // first delta), the tool calls the model emitted
        // (an immutable view; the hook can build a
        // modified copy), and the iteration index.
        POST_STREAM_END
    }

    Kind kind();

    CompletableFuture<Outcome> run(HookContext ctx);

    /** A hook's verdict. {@code continue} proceeds; {@code block} stops the action;
     *  {@code continueWithResult} replaces the action's result with a new one
     *  (used by mutation hooks to rewrite the tool result body); {@code async}
     *  resolves later — the action proceeds as if {@code Continue} was returned,
     *  and the deferred outcome (if any) is applied to the result stream
     *  asynchronously. */
    sealed interface Outcome {
        record Continue() implements Outcome {}
        record Block(String reason) implements Outcome {}
        /** deferred outcome. The action proceeds immediately
         *  (the engine treats this as {@link Continue}); the
         *  {@code future} resolves to a "real" outcome that the
         *  hook runtime applies later — typically a
         *  {@link ContinueWithResult} that lands in the tool
         *  transcript as a late annotation, or a {@link Block}
         *  that gets re-raised as an exception if the hook
         *  comes back with a "no" after the fact.
         *
         *  <p>Use this for hooks that should NOT block the model
         *  loop but DO want to surface side effects (e.g. a
         *  remote-audit hook that ships the edit to a SIEM
         *  asynchronously, or a slow background lint that
         *  post-fixes the tool result when it finishes). The
         *  shape is intentionally minimal: just the future.
         *  The runtime is responsible for attaching a default
         *  executor (or letting the caller pass one — see
         *  {@link #async(CompletableFuture, java.util.concurrent.Executor)}).
         */
        record Async(CompletableFuture<Outcome> future) implements Outcome {
            public Async {
                if (future == null) throw new IllegalArgumentException("future must not be null");
            }
            /** Convenience: defer with the common-case fork-pool
             *  executor. Equivalent to {@code new Async(future)}. */
            public static Async of(CompletableFuture<Outcome> future) {
                return new Async(future);
            }
        }
        /** prior round: same as {@link Continue} but the hook wants
         *  one or more fields of the action's result replaced.
         *  Each field is independently nullable: {@code null} means
         *  "keep the original value", a non-null value means
         *  "replace the original with this one". The bridge in
         *  {@code Hooks.asPostBridge} builds a fresh
         *  {@code ToolResult} that applies only the non-null
         *  mutations and preserves the rest (so a failed edit that
         *  a hook appends a hint to is still a failure — the model
         *  needs to know the edit failed even after the hint).
         *
         *  <p>For the common single-field case (just rewrite the
         *  text body) the legacy single-arg constructor is
         *  preserved. New callers should prefer the named factory
         *  methods on this record ({@link #replaceOutput},
         *  {@link #markError}, {@link #clearAttachments}, …) which
         *  read more clearly than the canonical all-args form.
         *
         *  <p>Field reference (against {@code Tool.ToolResult}):
         *  <ul>
         *    <li>{@code newOutput} ↔ {@code result.output()}</li>
         *    <li>{@code newIsError} ↔ {@code result.isError()}</li>
         *    <li>{@code newAttachments} ↔ {@code result.attachments()}</li>
         *  </ul> */
        record ContinueWithResult(String newOutput, Boolean newIsError, List<Tool.Attachment> newAttachments)
                implements Outcome {

            /** legacy single-arg constructor preserved for
             *  existing callers and tests. Mutates only the text
             *  body; {@code isError} and {@code attachments} are
             *  inherited from the original result. */
            public ContinueWithResult(String newOutput) { this(newOutput, null, null); }

            /** Replace the text body only. {@code null} clears the
             *  output (the bridge will substitute an empty string). */
            public static ContinueWithResult replaceOutput(String newOutput) {
                return new ContinueWithResult(newOutput, null, null);
            }

            /** Flip {@code isError} to true without touching the
             *  text body. Useful when a hook wants to escalate a
             *  silent failure into a visible one. */
            public static ContinueWithResult markError() {
                return new ContinueWithResult(null, Boolean.TRUE, null);
            }

            /** Flip {@code isError} to false (i.e. mark a tool
             *  result that failed at the Java level as a success
             *  from the model's point of view). Use sparingly —
             *  the model's recovery loop typically wants the
             *  error flag intact. */
            public static ContinueWithResult markSuccess() {
                return new ContinueWithResult(null, Boolean.FALSE, null);
            }

            /** Clear the UI attachment list. Used when the
             *  hook's mutation makes the original attachments
             *  stale (e.g. an edit hook that wants to drop the
             *  preview image because the file changed). */
            public static ContinueWithResult clearAttachments() {
                return new ContinueWithResult(null, null, List.of());
            }

            /** Replace the attachment list with a new one. Pass
             *  an empty list to clear; pass {@code null} to keep
             *  the original list. */
            public static ContinueWithResult replaceAttachments(List<Tool.Attachment> newAttachments) {
                return new ContinueWithResult(null, null, newAttachments);
            }

            /** Combined: rewrite the body AND flip the error
             *  flag. A common pattern for "I want the model to
             *  see this hint as an error so it retries". */
            public static ContinueWithResult replaceAsError(String newOutput) {
                return new ContinueWithResult(newOutput, Boolean.TRUE, null);
            }

            /** Combined: rewrite the body AND mark the result as
             *  a success. Used by hooks that turn a non-fatal
             *  warning into a clean completion message. */
            public static ContinueWithResult replaceAsSuccess(String newOutput) {
                return new ContinueWithResult(newOutput, Boolean.FALSE, null);
            }
        }
    }

    /**
     * Payload for the hook. The same record shape is shared across
     * every Kind; for kinds that don't apply a field, it's
     * {@code null} (e.g. {@code result} is null for PRE_TOOL_USE,
     * {@code todoList} is null for PRE/POST tool-use and STOP).
     */
    record HookContext(
            String sessionId,
            String toolName,
            Map<String, Object> toolInput,
            Tool.ToolResult result,
            // SESSION_IDLE carries the run's stop reason and the
            // current todo list (read from AppState). Empty list
            // means "no plan was written" — the typical case for
            // short, single-step prompts.
            String stopReason,
            List<Map<String, Object>> todoList,
            // PRE_MODEL_QUERY and POST_STREAM_END
            // piggyback on the same record. The
            // fields are nullable for the older kinds and
            // populated by the new factories. Keeping a
            // single record (vs. per-kind records) means
            // existing hooks see no signature change.
            String modelId,
            Integer iteration,
            String modelText,
            List<Map<String, Object>> toolCalls
    ) {
        public static HookContext forPre(String sessionId, String toolName, Map<String, Object> toolInput) {
            return new HookContext(sessionId, toolName, toolInput, null, null, null, null, null, null, null);
        }
        public static HookContext forPost(String sessionId, String toolName, Map<String, Object> toolInput, Tool.ToolResult result) {
            return new HookContext(sessionId, toolName, toolInput, result, null, null, null, null, null, null);
        }
        public static HookContext forStop(String sessionId) {
            return new HookContext(sessionId, null, null, null, null, null, null, null, null, null);
        }
        public static HookContext forUserPromptSubmit(String sessionId, String prompt) {
            return new HookContext(sessionId, null, Map.of("prompt", prompt == null ? "" : prompt), null, null, null, null, null, null, null);
        }
        // convenience constructor for the new SESSION_IDLE kind.
        public static HookContext forSessionIdle(String sessionId, String stopReason, List<Map<String, Object>> todoList) {
            return new HookContext(sessionId, null, null, null, stopReason,
                    todoList == null ? List.of() : todoList,
                    null, null, null, null);
        }
        // convenience constructor for the PRE_MODEL_QUERY
        // kind. Carries the model id, iteration index, and a
        // snapshot of the messages (kept in {@code toolInput} as
        // {@code Map.of("messages", List.of(Message))} for backward
        // compatibility with hooks that already read toolInput —
        // the runtime routes them into the dedicated
        // {@code toolCalls} / {@code modelText} fields when the
        // kind is one of the new ones).
        public static HookContext forPreModelQuery(
                String sessionId,
                String modelId,
                int iteration,
                List<Map<String, Object>> messages
        ) {
            Map<String, Object> input = Map.of(
                    "modelId", modelId == null ? "" : modelId,
                    "iteration", iteration,
                    "messages", messages == null ? List.of() : List.copyOf(messages)
            );
            return new HookContext(sessionId, null, input, null, null, null,
                    modelId, iteration, null, null);
        }
        // convenience constructor for the POST_STREAM_END
        // kind. Carries the model's emitted text and the tool
        // calls it decided to dispatch. {@code toolCalls} is
        // exposed both as a top-level field (preferred) and via
        // the {@code toolInput} map (for legacy hooks that read
        // from there).
        public static HookContext forPostStreamEnd(
                String sessionId,
                String modelId,
                int iteration,
                String modelText,
                List<Map<String, Object>> toolCalls
        ) {
            Map<String, Object> input = Map.of(
                    "modelId", modelId == null ? "" : modelId,
                    "iteration", iteration,
                    "modelText", modelText == null ? "" : modelText,
                    "toolCalls", toolCalls == null ? List.of() : List.copyOf(toolCalls)
            );
            return new HookContext(sessionId, null, input, null, null, null,
                    modelId, iteration,
                    modelText == null ? "" : modelText,
                    toolCalls == null ? List.of() : List.copyOf(toolCalls));
        }
    }
}
