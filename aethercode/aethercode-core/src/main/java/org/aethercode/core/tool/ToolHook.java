/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
