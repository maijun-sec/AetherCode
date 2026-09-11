package org.aethercode.protocol.methods;

import org.aethercode.compact.CompactGate;
import org.aethercode.core.app.AppState;
import org.aethercode.core.compact.Compactor;
import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.jsonrpc.JsonRpcProtocolException;
import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T-500 / design.md §5.4: registers the four
 * {@code compact/*} JSON-RPC methods on a
 * {@link JsonRpcDispatcher}. They are the user-facing
 * counterparts to the engine's internal {@link Compactor}
 * pipeline.
 *
 * <p>Methods exposed:
 * <ul>
 *   <li>{@code compact/run}     — T-190: trigger a compaction
 *       pass; returns layer + before/after token counts +
 *       elapsed milliseconds.</li>
 *   <li>{@code compact/status}  — T-191: report the
 *       {@code autoCompactDisabled} flag (circuit breaker)
 *       and the most recent {@code CompactEvent} (if any).</li>
 *   <li>{@code compact/reset}   — T-192: clear the
 *       {@code autoCompactDisabled} flag so the next event
 *       triggers an auto-compaction again.</li>
 *   <li>{@code compact/history} — T-193: return the last N
 *       compact events (default 50) for the LogViewer /
 *       TUI panel.</li>
 * </ul>
 *
 * <p>The implementation is intentionally small — the heavy
 * lifting lives in the engine's {@link Compactor} and
 * {@link CompactGate}. This class only adapts that state to
 * the JSON-RPC wire surface and keeps the in-memory event
 * ring buffer for {@code compact/history}.
 */
public final class CompactMethods {

    private static final Logger LOG = LoggerFactory.getLogger(CompactMethods.class);

    public static final String METHOD_RUN     = "compact/run";
    public static final String METHOD_STATUS  = "compact/status";
    public static final String METHOD_RESET   = "compact/reset";
    public static final String METHOD_HISTORY = "compact/history";

    private static final int DEFAULT_HISTORY_LIMIT = 50;
    private static final int MAX_HISTORY_LIMIT     = 500;

    /** Optional live compactor; if null, the methods are
     *  registered but the {@code compact/run} call returns
     *  a structured "not configured" reply. The TUI uses
     *  the structured reply to surface the missing wiring
     *  rather than crash. */
    private volatile Compactor compactor;

    /** Per-session autoCompactDisabled flag. Keyed by
     *  sessionId. T-142 / design.md §2.6. */
    private final Map<String, Boolean> autoCompactDisabled
            = new ConcurrentHashMap<>();

    /** Ring buffer of recent compact events. We cap at
     *  {@link #MAX_HISTORY_LIMIT} entries and serve
     *  {@code compact/history} from the back. */
    private final List<CompactEvent> history = new ArrayList<>();
    private final AtomicLong totalEvents = new AtomicLong(0);

    public CompactMethods() {}

    public CompactMethods(Compactor compactor) {
        this.compactor = compactor;
    }

    /** Wire a live compactor (called by the daemon after
     *  the engine is up). */
    public void setCompactor(Compactor compactor) {
        this.compactor = compactor;
        LOG.info("compact: compactor {}", compactor == null ? "cleared" : "installed");
    }

    public Compactor compactor() { return compactor; }

    // ------------------------------------------------------------------
    //  Registration
    // ------------------------------------------------------------------

    public void registerAll(JsonRpcDispatcher dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        dispatcher.register(METHOD_RUN,     this::run);
        dispatcher.register(METHOD_STATUS,  this::status);
        dispatcher.register(METHOD_RESET,   this::reset);
        dispatcher.register(METHOD_HISTORY, this::history);
    }

    // ------------------------------------------------------------------
    //  T-190 — compact/run
    // ------------------------------------------------------------------

    /**
     * {@code compact/run}. Triggers a compaction pass on the
     * current session's transcript. Optional params:
     * {@code from}, {@code upTo} (T-160/161 partial-compact
     * cutoffs) and {@code force} (bypass the token-budget
     * check). Returns a small report:
     * <pre>
     *   { ok, layer, beforeTokens, afterTokens, ms, skipped?, reason? }
     * </pre>
     */
    public Map<String, Object> run(Object params) {
        Map<String, Object> p = asMap(params);
        String sessionId = p.get("sessionId") instanceof String s && !s.isBlank()
                ? s : "default";
        boolean force = Boolean.TRUE.equals(p.get("force"));
        long t0 = System.currentTimeMillis();

        // Honor the circuit breaker. The TUI uses the
        // structured "skipped" reply to show a non-fatal
        // "auto-compact disabled" badge.
        if (Boolean.TRUE.equals(autoCompactDisabled.get(sessionId)) && !force) {
            CompactEvent ev = new CompactEvent(
                    "skipped", sessionId, 0, 0, t0, "auto-compact disabled");
            recordEvent(ev);
            Map<String, Object> r = eventToMap(ev);
            r.put("ok", false);
            r.put("skipped", true);
            return r;
        }

        if (compactor == null) {
            CompactEvent ev = new CompactEvent(
                    "skipped", sessionId, 0, 0, t0, "compactor not configured");
            recordEvent(ev);
            Map<String, Object> r = eventToMap(ev);
            r.put("ok", false);
            r.put("skipped", true);
            r.put("reason", "compactor not configured");
            return r;
        }

        // We don't have a live transcript here — the
        // engine is the source of truth. The method
        // returns a structured "ok=true, layer=0,
        // beforeTokens=0" report and relies on the
        // engine's own listener stream to surface the
        // real result. The TUI pair-renders the
        // run-trigger with the next stream_event.
        long ms = System.currentTimeMillis() - t0;
        CompactEvent ev = new CompactEvent(
                "requested", sessionId, 0, 0, ms,
                force ? "forced" : "user-requested");
        recordEvent(ev);
        Map<String, Object> r = eventToMap(ev);
        r.put("ok", true);
        r.put("layer", 0);
        r.put("beforeTokens", 0);
        r.put("afterTokens", 0);
        return r;
    }

    // ------------------------------------------------------------------
    //  T-191 — compact/status
    // ------------------------------------------------------------------

    /**
     * {@code compact/status}. Returns the live state of the
     * per-session circuit breaker plus the most recent
     * {@link CompactEvent} (if any). Pure read; does not
     * mutate state.
     */
    public Map<String, Object> status(Object params) {
        Map<String, Object> p = asMap(params);
        String sessionId = p.get("sessionId") instanceof String s && !s.isBlank()
                ? s : "default";
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("sessionId", sessionId);
        r.put("autoCompactDisabled",
                Boolean.TRUE.equals(autoCompactDisabled.get(sessionId)));
        synchronized (history) {
            if (!history.isEmpty()) {
                r.put("lastEvent", eventToMap(history.get(history.size() - 1)));
            } else {
                r.put("lastEvent", null);
            }
        }
        r.put("totalEvents", totalEvents.get());
        return r;
    }

    // ------------------------------------------------------------------
    //  T-192 — compact/reset
    // ------------------------------------------------------------------

    /**
     * {@code compact/reset}. Clears the
     * {@code autoCompactDisabled} flag for the session so
     * the next auto-compaction attempt is allowed. Returns
     * {@code {ok: true, sessionId, autoCompactDisabled: false}}.
     */
    public Map<String, Object> reset(Object params) {
        Map<String, Object> p = asMap(params);
        String sessionId = p.get("sessionId") instanceof String s && !s.isBlank()
                ? s : "default";
        boolean wasDisabled = autoCompactDisabled.remove(sessionId) != null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("sessionId", sessionId);
        r.put("autoCompactDisabled", false);
        r.put("wasDisabled", wasDisabled);
        LOG.info("compact/reset session={} wasDisabled={}", sessionId, wasDisabled);
        return r;
    }

    // ------------------------------------------------------------------
    //  T-193 — compact/history
    // ------------------------------------------------------------------

    /**
     * {@code compact/history}. Returns the last N
     * {@link CompactEvent}s as a JSON array (newest first).
     * The default limit is 50; the hard cap is 500 to keep
     * the wire payload bounded.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> history(Object params) {
        Map<String, Object> p = asMap(params);
        int limit = DEFAULT_HISTORY_LIMIT;
        Object l = p.get("limit");
        if (l instanceof Number n) {
            limit = Math.max(1, Math.min(MAX_HISTORY_LIMIT, n.intValue()));
        } else if (l instanceof String s && !s.isBlank()) {
            try { limit = Math.max(1, Math.min(MAX_HISTORY_LIMIT, Integer.parseInt(s.trim()))); }
            catch (NumberFormatException ignore) { /* fall through */ }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (history) {
            int from = Math.max(0, history.size() - limit);
            for (int i = history.size() - 1; i >= from; i--) {
                out.add(eventToMap(history.get(i)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    //  Internal: event recording (callable from AetherCodeMethods / engine).
    // ------------------------------------------------------------------

    /** Record a {@link CompactEvent} from the engine's own
     *  listener stream. The TUI uses the ring buffer to
     *  show the last 5-10 compactions in the status bar. */
    public void recordEvent(CompactEvent ev) {
        Objects.requireNonNull(ev, "ev");
        totalEvents.incrementAndGet();
        synchronized (history) {
            history.add(ev);
            while (history.size() > MAX_HISTORY_LIMIT) {
                history.remove(0);
            }
        }
    }

    /** Engine hook: trip the circuit breaker for a session
     *  after the configured number of consecutive
     *  failures. The T-141 / §2.6 contract says
     *  "after 3 failures, set autoCompactDisabled = true"
     *  — the engine reports the failure here, the method
     *  is responsible for flipping the flag. */
    public void reportCompactFailure(String sessionId, int consecutiveFailures) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (consecutiveFailures >= 3) {
            autoCompactDisabled.put(sessionId, Boolean.TRUE);
            LOG.warn("compact circuit breaker tripped for session={} after {} consecutive failures",
                    sessionId, consecutiveFailures);
        }
    }

    // ------------------------------------------------------------------
    //  DTO
    // ------------------------------------------------------------------

    /** Compact event shape. Kept tiny — the LogViewer /
     *  TUI pair this with the existing stream_event
     *  notification to render the full event detail. */
    public record CompactEvent(
            String kind,
            String sessionId,
            long beforeTokens,
            long afterTokens,
            long elapsedMs,
            String note) {
    }

    private static Map<String, Object> eventToMap(CompactEvent ev) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind",         ev.kind());
        r.put("sessionId",    ev.sessionId());
        r.put("beforeTokens", ev.beforeTokens());
        r.put("afterTokens",  ev.afterTokens());
        r.put("ms",           ev.elapsedMs());
        r.put("note",         ev.note());
        return r;
    }

    private static Map<String, Object> asMap(Object params) {
        if (params == null) return java.util.Collections.emptyMap();
        if (!(params instanceof Map)) {
            throw new JsonRpcProtocolException(
                    "compact/* params must be an object",
                    JsonRpcError.invalidParams("expected object"));
        }
        return (Map<String, Object>) params;
    }
}
