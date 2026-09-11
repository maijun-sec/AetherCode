/**
 * Circuit breaker for auto-compaction (T-140 → T-144).
 *
 * Per `design.md §2.6` and `spec.md §2.5`:
 *
 * - In-memory per-session counter: `consecutiveCompactFailures`.
 * - After 3 consecutive failures within the same session, the
 *   session-level flag `autoCompactDisabled` is set to `true`.
 * - Until the user explicitly clears the flag (via
 *   `aethercode compact reset`), all future auto-compaction is bypassed.
 * - The TUI surfaces a persistent warning while the flag is set.
 *
 * The class is in-memory only; persistence is the caller's job (it
 * survives a single process lifetime, not a restart).
 */
/** Default failure threshold — must match `design.md §2.6`. */
export const DEFAULT_FAILURE_THRESHOLD = 3;
/** Default options for `CircuitBreaker`. */
export function defaultCircuitBreakerOptions() {
    return { threshold: DEFAULT_FAILURE_THRESHOLD };
}
const FAILURE_HISTORY_LIMIT = 16;
export class CircuitBreaker {
    threshold;
    now;
    bySession = new Map();
    constructor(options = defaultCircuitBreakerOptions()) {
        this.threshold = Math.max(1, Math.floor(options.threshold ?? DEFAULT_FAILURE_THRESHOLD));
        this.now = options.now ?? Date.now;
    }
    /**
     * Record a successful compaction. Resets the consecutive-failure
     * counter for the session and clears the disabled flag.
     */
    recordSuccess(sessionId) {
        const state = this.getOrCreate(sessionId);
        state.consecutiveCompactFailures = 0;
        state.autoCompactDisabled = false;
    }
    /**
     * Record a failed compaction. Increments the counter, sets the
     * disabled flag when the threshold is reached, and pushes a record
     * onto the ring buffer.
     *
     * Returns the resulting status snapshot for the session.
     */
    recordFailure(sessionId, reason = "unknown") {
        const state = this.getOrCreate(sessionId);
        const ts = this.now();
        state.consecutiveCompactFailures += 1;
        state.lastFailureTs = ts;
        state.failureHistory.push({ ts, reason });
        if (state.failureHistory.length > FAILURE_HISTORY_LIMIT) {
            state.failureHistory.splice(0, state.failureHistory.length - FAILURE_HISTORY_LIMIT);
        }
        if (state.consecutiveCompactFailures >= this.threshold) {
            state.autoCompactDisabled = true;
        }
        return this.status(sessionId);
    }
    /**
     * Returns true when the session has tripped the breaker. The
     * pipeline / TUI uses this to bypass auto-compaction.
     */
    isTripped(sessionId) {
        return this.bySession.get(sessionId)?.autoCompactDisabled === true;
    }
    /**
     * Returns the status snapshot for a session. Missing sessions get a
     * zeroed, enabled snapshot.
     */
    status(sessionId) {
        const state = this.bySession.get(sessionId);
        if (!state) {
            return {
                consecutiveCompactFailures: 0,
                autoCompactDisabled: false,
                lastFailureTs: null,
            };
        }
        return {
            consecutiveCompactFailures: state.consecutiveCompactFailures,
            autoCompactDisabled: state.autoCompactDisabled,
            lastFailureTs: state.lastFailureTs,
        };
    }
    /**
     * Clears the consecutive-failure counter AND the disabled flag for
     * a session. The TUI command `aethercode compact reset` calls this
     * (T-144).
     */
    reset(sessionId) {
        this.bySession.delete(sessionId);
    }
    /**
     * Build a TUI-friendly warning string. Returns `null` when no
     * warning should be shown (T-143). The text is intentionally short
     * and free of emoji so the caller can render it on a single line.
     */
    warningMessage(sessionId) {
        const s = this.status(sessionId);
        if (!s.autoCompactDisabled) {
            return null;
        }
        return `Auto-compact disabled after ${s.consecutiveCompactFailures} consecutive failures. Run 'aethercode compact reset' to re-enable.`;
    }
    /** Test-only: returns the recent failure history. */
    failureHistory(sessionId) {
        return this.bySession.get(sessionId)?.failureHistory ?? [];
    }
    getOrCreate(sessionId) {
        let state = this.bySession.get(sessionId);
        if (!state) {
            state = {
                consecutiveCompactFailures: 0,
                autoCompactDisabled: false,
                lastFailureTs: null,
                failureHistory: [],
            };
            this.bySession.set(sessionId, state);
        }
        return state;
    }
}
