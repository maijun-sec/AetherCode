package org.aethercode.partner.quickjs.js;

import java.util.Set;

/**
 * A QuickJS runtime: a heap + garbage collector that can host many
 * {@link JsContext}s.
 *
 * <p>1:1 port of <code>quickjs_rs.Runtime</code>. The runtime is
 * created on a {@link JsWorker}'s thread and may only be used from
 * that thread; the worker handles dispatch.</p>
 */
public interface JsRuntime {

    /**
     * Memory limit in bytes shared across all {@link JsContext}s under
     * this runtime. A context allocation that would exceed the limit
     * throws {@link JsMemoryLimitException}.
     */
    int memoryLimit();

    /** Source transform flags applied to every {@code eval} under this runtime. */
    Set<JsSourceTransform> transformFlags();

    /**
     * Create a new {@link JsContext} that shares the runtime's heap
     * with the configured per-call {@code timeout} (seconds).
     */
    JsContext newContext(double timeout);

    /**
     * Restore {@code snapshot} into {@code context}, optionally
     * re-injecting the standard {@code globalThis} (console, tools,
     * task) bindings. Used to rehydrate a previous turn's REPL state.
     */
    void restoreSnapshot(JsSnapshot snapshot, JsContext context, boolean injectGlobals);

    /** Free the runtime and all its contexts. After this call the runtime is unusable. */
    void close();
}
