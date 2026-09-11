package org.aethercode.sdk;

import org.aethercode.core.concurrency.EngineStats;

/**
 * thrown by {@link AetherCodeEngine#query(String)} when
 * the engine is backpressured (memory above the configured
 * threshold). The {@code AetherCodeMethods.query} RPC catches
 * this and surfaces it as a structured JSON-RPC error so the
 * desktop can render a "System busy, please retry later" pill and (optionally)
 * a "Reduce concurrency profile" affordance.
 *
 * <p>This is a {@link RuntimeException} so it propagates out of
 * the streaming {@code query()} return value. The desktop's
 * store handler is already in the path that handles RPC
 * errors, so no separate try/catch is required at the call site.
 */
public class BackpressureException extends RuntimeException {

    private final EngineStats stats;

    public BackpressureException(String message, EngineStats stats) {
        super(message);
        this.stats = stats;
    }

    /** Snapshot of the engine stats at the time the query was
     *  refused. Useful for the desktop's diagnostic pill
     *  ("Memory 92% · 1 query in flight · 0 tools in flight"). */
    public EngineStats stats() { return stats; }
}
