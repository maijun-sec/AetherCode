/**
 * Compact RPC surface (T-190 → T-194).
 *
 * Per `design.md §2.9` and `spec.md §2.7`:
 *
 *   "compact/run":    { from?, upTo?, force? } → { layer, beforeTokens, afterTokens, ms }
 *   "compact/status": void                     → { autoCompactDisabled, lastEvent }
 *   "compact/reset":  void                     → { ok: true }
 *   "compact/history":{ limit? }               → CompactEvent[]
 *
 * This module is intentionally transport-agnostic. The daemon
 * (Java `aethercode-protocol` or a local JSON-RPC server) calls
 * `handle(method, params)`; the `JsonRpcClient` in the TUI calls
 * the matching method over stdio. Both sides share the same types
 * from this file.
 *
 * The implementation is in-process: it composes the
 * `CompactPipeline` + `CircuitBreaker` from earlier rounds and
 * keeps an in-memory ring buffer of `CompactEvent` records that
 * the TUI's `ContextMeter` samples every 2 seconds (T-180/T-183).
 */
/** Default ring-buffer size for the event log (T-193). */
export const DEFAULT_HISTORY_LIMIT = 200;
/** Standard error codes used by the compact RPC. */
export const RPC_ERR_UNKNOWN_METHOD = -32601;
export const RPC_ERR_INVALID_PARAMS = -32602;
export const RPC_ERR_INTERNAL = -32603;
export class CompactRpc {
    pipeline;
    breaker;
    sessionId;
    historyLimit;
    now;
    buildInput;
    llm;
    onEvent;
    store;
    events = [];
    constructor(options) {
        this.pipeline = options.pipeline;
        this.breaker = options.breaker;
        this.sessionId = options.sessionId;
        this.historyLimit = Math.max(1, Math.floor(options.historyLimit ?? DEFAULT_HISTORY_LIMIT));
        this.now = options.now ?? Date.now;
        this.buildInput = options.buildInput;
        this.llm = options.llm;
        this.onEvent = options.onEvent;
        this.store = options.store;
    }
    /**
     * Dispatch a JSON-RPC-style request. The `method` is the
     * compact method name (e.g. `"compact/run"`); the `params` is
     * whatever the caller sent. Returns a structured result.
     *
     * The transport layer (Java daemon or in-process) is expected
     * to map JSON-RPC errors from this:
     *   - `{ error: { code: -32601, ... } }` → method not found
     *   - `{ error: { code: -32602, ... } }` → bad params
     *   - `{ error: { code: -32603, ... } }` → internal failure
     */
    async handle(method, params) {
        switch (method) {
            case "compact/run":
                return await this.handleRun(params);
            case "compact/status":
                return this.handleStatus();
            case "compact/reset":
                return this.handleReset();
            case "compact/history":
                return this.handleHistory(params);
            default:
                return {
                    error: {
                        code: RPC_ERR_UNKNOWN_METHOD,
                        message: `unknown method: ${method}`,
                    },
                };
        }
    }
    /** Typed helper for `compact/run`. */
    run(params = {}) {
        return this.handleRun(params);
    }
    /** Typed helper for `compact/status`. */
    status() {
        return this.handleStatus();
    }
    /** Typed helper for `compact/reset`. */
    reset() {
        return this.handleReset();
    }
    /** Typed helper for `compact/history`. */
    history(params = {}) {
        return this.handleHistory(params);
    }
    /** The list of method names this RPC server handles.
     *  Used by the daemon to register the routes and by tests
     *  to assert the surface is complete (T-194). */
    static methods() {
        return ["compact/run", "compact/status", "compact/reset", "compact/history"];
    }
    /**
     * Synchronous accessor for the recorded events. Used by the
     * daemon to expose them to the TUI's `ContextMeter` over a
     * notification channel (`compact/event`) if it prefers
     * push-based delivery instead of polling.
     */
    eventLog() {
        return this.events;
    }
    /** Test helper: append a synthetic event. Production code should
     *  use the `handle("compact/run", ...)` path; tests use this to
     *  seed the history without spinning up an LLM. */
    pushTestEvent(event) {
        this.recordEvent(event);
    }
    // ---------- handlers -----------------------------------------
    async handleRun(params) {
        const parsed = parseRunParams(params);
        if (!parsed.ok) {
            return {
                error: {
                    code: RPC_ERR_INVALID_PARAMS,
                    message: "invalid compact/run params",
                    data: { issues: parsed.issues },
                },
            };
        }
        if (this.breaker.isTripped(this.sessionId)) {
            // Bypass per design.md §2.6. We still return a structured
            // result with `failed: true` and a clear reason.
            const result = {
                layer: 1,
                beforeTokens: 0,
                afterTokens: 0,
                ms: 0,
                failed: true,
                failureReason: "auto_compact_disabled",
            };
            // Don't pollute the history with no-op runs while the
            // breaker is tripped. Status still surfaces the
            // disabled flag.
            return { result };
        }
        let input;
        try {
            input = await this.buildInput();
        }
        catch (e) {
            // buildInput failure: treat as a compact failure event so the
            // breaker is notified and the TUI's ContextMeter shows the drop.
            const reason = `buildInput: ${e.message}`;
            this.breaker.recordFailure(this.sessionId, classifyError(reason));
            const event = {
                ts: this.now(),
                layer: 1,
                beforeTokens: 0,
                afterTokens: 0,
                elapsedMs: 0,
                failed: true,
                failureReason: reason,
            };
            this.recordEvent(event);
            return {
                result: {
                    layer: event.layer,
                    beforeTokens: 0,
                    afterTokens: 0,
                    ms: 0,
                    failed: true,
                    failureReason: reason,
                },
            };
        }
        const startedAt = this.now();
        let compact;
        try {
            compact = await this.pipeline.compact(input, {
                llm: this.llm,
                forceLayer3: parsed.value.force,
                fromId: parsed.value.from,
                upToId: parsed.value.upTo,
                now: this.now,
            });
        }
        catch (e) {
            const reason = e.message || "unknown";
            this.breaker.recordFailure(this.sessionId, classifyError(reason));
            const event = {
                ts: this.now(),
                layer: 1,
                beforeTokens: 0,
                afterTokens: 0,
                elapsedMs: this.now() - startedAt,
                failed: true,
                failureReason: reason,
            };
            this.recordEvent(event);
            return {
                result: {
                    layer: event.layer,
                    beforeTokens: 0,
                    afterTokens: 0,
                    ms: event.elapsedMs,
                    failed: true,
                    failureReason: reason,
                },
            };
        }
        const event = {
            ts: this.now(),
            layer: compact.layer,
            beforeTokens: compact.beforeTokens,
            afterTokens: compact.afterTokens,
            elapsedMs: this.now() - startedAt,
            failed: false,
        };
        this.breaker.recordSuccess(this.sessionId);
        this.recordEvent(event);
        return {
            result: {
                layer: compact.layer,
                beforeTokens: compact.beforeTokens,
                afterTokens: compact.afterTokens,
                ms: event.elapsedMs,
                failed: false,
            },
        };
    }
    handleStatus() {
        const breaker = this.breaker.status(this.sessionId);
        const lastEvent = this.events.length > 0 ? (this.events[this.events.length - 1] ?? null) : null;
        return {
            result: {
                autoCompactDisabled: breaker.autoCompactDisabled,
                consecutiveCompactFailures: breaker.consecutiveCompactFailures,
                lastFailureTs: breaker.lastFailureTs,
                lastEvent,
            },
        };
    }
    handleReset() {
        const before = this.breaker.status(this.sessionId);
        this.breaker.reset(this.sessionId);
        return {
            result: {
                ok: true,
                clearedFailures: before.consecutiveCompactFailures,
            },
        };
    }
    handleHistory(params) {
        const parsed = parseHistoryParams(params);
        if (!parsed.ok) {
            return {
                error: {
                    code: RPC_ERR_INVALID_PARAMS,
                    message: "invalid compact/history params",
                    data: { issues: parsed.issues },
                },
            };
        }
        const limit = parsed.value.limit ?? this.events.length;
        // When a persistent store is wired, consult it first (it
        // survives restarts). Fall back to the in-memory ring buffer
        // when the store is empty or unavailable.
        if (this.store) {
            try {
                const fromStore = this.store.recent({ sessionId: this.sessionId, limit });
                if (fromStore.length > 0)
                    return { result: fromStore };
            }
            catch { /* fall through to ring buffer */ }
        }
        const slice = this.events.slice(Math.max(0, this.events.length - Math.max(0, limit)));
        return { result: slice };
    }
    // ---------- internals -----------------------------------------
    recordEvent(event) {
        this.events.push(event);
        if (this.events.length > this.historyLimit) {
            this.events.splice(0, this.events.length - this.historyLimit);
        }
        if (this.store) {
            try {
                this.store.append({ ...event, sessionId: this.sessionId });
            }
            catch { /* swallow store errors so RPC stays live */ }
        }
        if (this.onEvent) {
            try {
                this.onEvent(event);
            }
            catch { /* swallow listener errors */ }
        }
    }
}
function parseRunParams(params) {
    if (params === undefined || params === null)
        return { ok: true, value: {} };
    if (typeof params !== "object")
        return { ok: false, issues: ["params must be an object"] };
    const p = params;
    const issues = [];
    const from = p["from"];
    const upTo = p["upTo"];
    const force = p["force"];
    if (from !== undefined && typeof from !== "string")
        issues.push("from must be a string");
    if (upTo !== undefined && typeof upTo !== "string")
        issues.push("upTo must be a string");
    if (force !== undefined && typeof force !== "boolean")
        issues.push("force must be a boolean");
    if (typeof from === "string" && typeof upTo === "string") {
        issues.push("from and upTo are mutually exclusive");
    }
    if (issues.length > 0)
        return { ok: false, issues };
    const out = {};
    if (typeof from === "string")
        out.from = from;
    if (typeof upTo === "string")
        out.upTo = upTo;
    if (typeof force === "boolean")
        out.force = force;
    return { ok: true, value: out };
}
function parseHistoryParams(params) {
    if (params === undefined || params === null)
        return { ok: true, value: {} };
    if (typeof params !== "object")
        return { ok: false, issues: ["params must be an object"] };
    const p = params;
    const limit = p["limit"];
    if (limit !== undefined) {
        if (typeof limit !== "number" || !Number.isInteger(limit) || limit < 0) {
            return { ok: false, issues: ["limit must be a non-negative integer"] };
        }
        return { ok: true, value: { limit } };
    }
    return { ok: true, value: {} };
}
/** Map a thrown error's `.message` to a `CircuitBreakerFailureReason`. */
function classifyError(message) {
    const m = message.toLowerCase();
    if (m.includes("timeout"))
        return "timeout";
    if (m.includes("validation"))
        return "validation_error";
    if (m.includes("backstop") || m.includes("retry"))
        return "backstop_retry_exhausted";
    if (m.includes("llm") || m.includes("model") || m.includes("network"))
        return "llm_error";
    return "unknown";
}
