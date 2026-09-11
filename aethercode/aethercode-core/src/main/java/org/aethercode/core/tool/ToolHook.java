package org.aethercode.core.tool;

import java.util.List;
import java.util.Map;

/**
 * a hook into the tool-call lifecycle. Hooks are pure functions
 * (no I/O) so they compose cleanly: the orchestrator runs all
 * {@code pre} hooks in order, executes the tool, then runs {@code post}
 * hooks in reverse registration order.
 *
 * <p>Use cases: deny-listing, audit logging, telemetry, sandboxed
 * transformations on input/output.
 */
public interface ToolHook {

    record Context(
            String toolName,
            Map<String, Object> input,
            /** mutable — a hook can attach facts for later hooks to read. */
            Map<String, Object> attributes
    ) {
        public Context {
            input = Map.copyOf(input == null ? Map.of() : input);
            attributes = attributes == null ? new java.util.HashMap<>() : attributes;
        }
    }

    record Result(
            Object output,
            boolean isError
    ) {
        public static Result ok(Object o)   { return new Result(o, false); }
        public static Result error(Object o){ return new Result(o, true); }
    }

    /** Optional filter: only fire for matching tool names. Empty/null matches all. */
    default List<String> toolFilter() { return List.of(); }

    /** Called before the tool runs. May mutate the input via the returned context. */
    default Context pre(Context ctx) { return ctx; }

    /** Called after the tool runs. May transform the result via the returned Result. */
    default Result post(Context ctx, Result result) { return result; }

    /**
     * Called when the orchestrator wants to abort a call before it runs.
     * Return a non-null {@link Result} to short-circuit; the tool will not
     * be invoked and the returned Result will be reported to the model.
     */
    default Result deny(Context ctx) { return null; }
}
