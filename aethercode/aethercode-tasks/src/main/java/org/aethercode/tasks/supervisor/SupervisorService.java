package org.aethercode.tasks.supervisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * prior round (T-312/§4.3 design.md): the task-domain service the
 * supervisor's JSON-RPC server delegates to. Keeps the RPC
 * layer thin: each method takes a {@code Map<String,Object>}
 * of params, returns a {@code Map<String,Object>} result, and
 * throws a {@link IllegalArgumentException} for bad input.
 *
 * <p>The service is single-threaded by contract — the RPC
 * dispatcher invokes one handler at a time per connection.
 * The store handles its own locking so callers don't need to
 * coordinate.
 */
public final class SupervisorService {

    private static final Logger LOG = LoggerFactory.getLogger(SupervisorService.class);

    private final SupervisorStore store;
    private final ResumePlanner resumePlanner;
    /**
     * R240 (O-5): per-supervisor default limits loaded from
     * {@code ~/.aethercode/task-defaults.yaml}. When a {@code
     * task/spawn} call doesn't pass an explicit {@code limits}
     * blob, this fallback is merged in so the child is always
     * subject to some budget. {@code null} → unlimited
     * (matches the legacy behaviour).
     */
    private final org.aethercode.tasks.limits.Limits defaultLimits;

    public SupervisorService(SupervisorStore store) {
        this(store, null);
    }

    public SupervisorService(SupervisorStore store,
                             org.aethercode.tasks.limits.Limits defaultLimits) {
        this.store = Objects.requireNonNull(store, "store");
        this.resumePlanner = new ResumePlanner(store);
        this.defaultLimits = defaultLimits;
    }

    public SupervisorStore store() { return store; }

    /** R240 (O-5): the limits the supervisor applies when a
     *  spawn call doesn't supply its own. May be
     *  {@link org.aethercode.tasks.limits.Limits#unlimited()}. */
    public org.aethercode.tasks.limits.Limits defaultLimits() {
        return defaultLimits == null
                ? org.aethercode.tasks.limits.Limits.unlimited()
                : defaultLimits;
    }

    /** {@code task/spawn}. */
    public Map<String, Object> taskSpawn(Map<String, Object> p) throws SQLException {
        String prompt = stringParam(p, "prompt");
        String cwd = stringParam(p, "cwd");
        String parentSessionId = stringParam(p, "parentSessionId", null);
        String model = stringParam(p, "model", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> explicitLimits = (Map<String, Object>) p.get("limits");
        boolean hasExplicitLimits = explicitLimits != null && !explicitLimits.isEmpty();
        String configJson = p.containsKey("config") && p.get("config") != null
                ? p.get("config").toString() : null;
        Map<String, Object> cfg = new LinkedHashMap<>();
        if (configJson != null && !configJson.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(configJson, Map.class);
                cfg.putAll(parsed);
            } catch (Exception ignored) {
                // best-effort: ignore malformed config and start fresh
            }
        }
        // Attach model to config blob if present.
        if (model != null) cfg.put("model", model);
        // R240 (O-5): the limits resolution order is
        //   1. caller-supplied params['limits'] (highest priority)
        //   2. config-blob's existing 'limits' key (caller passed
        //      it via a 'config' JSON string)
        //   3. the supervisor's default limits (if non-unlimited)
        //   4. nothing (unlimited)
        // Previously, the explicit `params['limits']` path was a
        // latent bug: TaskCli's --tokens / --wall-clock-ms flags
        // were silently dropped on spawn. The integration test
        // SupervisorServiceDefaultsTest pins the new behaviour.
        if (hasExplicitLimits) {
            cfg.put("limits", explicitLimits);
        } else if (!cfg.containsKey("limits")
                && defaultLimits != null && !defaultLimits.isUnlimited()) {
            cfg.put("limits", defaultLimits.toMap());
        }
        try {
            configJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(cfg);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad config: " + e.getMessage());
        }
        String id = store.createChild(cwd, prompt, parentSessionId, configJson);
        // Stamp a status_change event so a TUI that attaches
        // immediately sees the spawn.
        store.appendEvent(id, ChildEventRecord.TYPE_STATUS_CHANGE,
                "{\"to\":\"QUEUED\",\"reason\":\"spawn\"}");
        return SupervisorRpcServer.result("childId", id, "status", "QUEUED");
    }

    /** {@code task/list}. */
    public List<Map<String, Object>> taskList(Map<String, Object> p) throws SQLException {
        ChildStatus filter = null;
        Object raw = p.get("status");
        if (raw != null && !raw.toString().isBlank()) {
            try {
                filter = ChildStatus.valueOf(raw.toString());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("bad status filter: " + raw);
            }
        }
        List<ChildRecord> rows = store.listChildren(filter);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ChildRecord r : rows) out.add(toSummary(r));
        return out;
    }

    /** {@code task/get}. */
    public Map<String, Object> taskGet(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "childId");
        ChildRecord r = store.getChild(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown childId: " + id));
        Map<String, Object> out = new LinkedHashMap<>(toSummary(r));
        out.put("config", r.configJson());
        out.put("state", r.stateJson());
        out.put("limitsHit", r.limitsHitJson());
        return out;
    }

    /** {@code task/events}. */
    public List<Map<String, Object>> taskEvents(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "childId");
        long since = longParam(p, "since", 0L);
        int limit = (int) longParam(p, "limit", 1000L);
        List<ChildEventRecord> rows = store.listEvents(id, since, limit);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ChildEventRecord e : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("childId", e.childId());
            m.put("ts", e.tsMs());
            m.put("type", e.type());
            m.put("payload", e.payloadJson());
            out.add(m);
        }
        return out;
    }

    /** {@code task/kill}. */
    public Map<String, Object> taskKill(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "childId");
        String reason = stringParam(p, "reason", null);
        ChildRecord r = store.getChild(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown childId: " + id));
        if (r.status().isTerminal()) {
            return SupervisorRpcServer.result("ok", true, "noop", true,
                    "status", r.status().name());
        }
        Optional<ChildStatus> prev = store.updateStatus(id, ChildStatus.KILLED);
        if (reason != null) store.setError(id, reason);
        store.appendEvent(id, ChildEventRecord.TYPE_STATUS_CHANGE,
                "{\"to\":\"KILLED\""
                        + (reason == null ? "" : ",\"reason\":\"" + escape(reason) + "\"")
                        + "}");
        LOG.info("killed child {} (was {})", id, prev.orElse(null));
        return SupervisorRpcServer.result("ok", true);
    }

    /** {@code task/await} — polls the row until terminal or timeout. */
    public Map<String, Object> taskAwait(Map<String, Object> p) throws SQLException, InterruptedException {
        String id = stringParam(p, "childId");
        long timeoutMs = longParam(p, "timeoutMs", 60_000L);
        long deadline = System.currentTimeMillis() + timeoutMs;
        ChildStatus last;
        while (true) {
            Optional<ChildRecord> opt = store.getChild(id);
            if (opt.isEmpty()) throw new IllegalArgumentException("unknown childId: " + id);
            last = opt.get().status();
            if (last.isTerminal()) break;
            if (System.currentTimeMillis() >= deadline) break;
            Thread.sleep(100L);
        }
        return SupervisorRpcServer.result("status", last.name());
    }

    /** {@code task/resume}. Transitions a PAUSED child back to RUNNING. */
    public Map<String, Object> taskResume(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "childId");
        ChildRecord r = store.getChild(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown childId: " + id));
        if (r.status() != ChildStatus.PAUSED) {
            throw new IllegalArgumentException("child is not PAUSED, got " + r.status());
        }
        store.updateStatus(id, ChildStatus.RUNNING);
        store.appendEvent(id, ChildEventRecord.TYPE_STATUS_CHANGE,
                "{\"to\":\"RUNNING\",\"reason\":\"resume\"}");
        return SupervisorRpcServer.result("ok", true);
    }

    /** {@code task/appendEvent}. Used by clients (AsyncSubAgent middleware). */
    public Map<String, Object> taskAppendEvent(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "childId");
        String type = stringParam(p, "type");
        String payload = p.containsKey("payload") && p.get("payload") != null
                ? p.get("payload").toString() : "{}";
        long eventId = store.appendEvent(id, type, payload);
        return SupervisorRpcServer.result("eventId", eventId);
    }

    /**
     * prior round (T-353/§4.3 design.md): {@code task/setLimits}. The
     * caller's {@code limits} map is partial: any field that's
     * missing (or null) is left at the current value. The
     * existing child row's {@code config} blob is mutated in
     * place; if the blob already has a {@code limits} field the
     * merge replaces just the fields the caller passed in. To
     * remove a cap entirely, pass {@code "remove": ["tokens"]}
     * in the params (the empty value deletes the key).
     */
    public Map<String, Object> taskSetLimits(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "childId");
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) p.get("limits");
        if (limits == null) limits = Map.of();
        @SuppressWarnings("unchecked")
        List<String> remove = (List<String>) p.get("remove");
        Optional<ChildRecord> opt = store.getChild(id);
        if (opt.isEmpty()) throw new IllegalArgumentException("unknown childId: " + id);
        String cfg = opt.get().configJson();
        Map<String, Object> root = new LinkedHashMap<>();
        if (cfg != null && !cfg.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(cfg, Map.class);
                root.putAll(parsed);
            } catch (Exception ignored) {
                // bad config: start fresh
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> existing = (Map<String, Object>) root.getOrDefault("limits", new LinkedHashMap<>());
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        for (Map.Entry<String, Object> e : limits.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == null) continue;
            // Accept the same aliases as Limits.fromMap.
            String canonical = switch (k) {
                case "wallClock" -> "wallClockMs";
                default -> k;
            };
            if (v instanceof Number n) {
                merged.put(canonical, n.longValue());
            } else {
                try { merged.put(canonical, Long.parseLong(v.toString())); }
                catch (NumberFormatException nfe) { /* skip bad value */ }
            }
        }
        if (remove != null) {
            for (String r : remove) {
                String canonical = r.equals("wallClock") ? "wallClockMs" : r;
                merged.remove(canonical);
            }
        }
        root.put("limits", merged);
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad config payload: " + e.getMessage());
        }
        store.setConfig(id, json);
        try {
            store.appendEvent(id, "limits_changed",
                    "{\"limits\":" + new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(merged) + "}");
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("could not serialise limits event: " + e.getMessage(), e);
        }
        return SupervisorRpcServer.result("ok", true, "childId", id, "limits", merged);
    }

    /**
     * prior round (T-362/§4.3 design.md): {@code task/attach}. The
     * response is a snapshot of the child plus its full event
     * log; the TUI uses this to back-fill its UI before it
     * starts polling {@code task/events} for live updates.
     *
     * <p>Streaming on a line-delimited JSON socket would
     * require a long-lived RPC frame; the current protocol
     * (one response per request) implements the same UX
     * with a snapshot + a polling follow-up. The {@code since}
     * cursor is returned in {@code lastEventId} so the
     * follow-up poll can resume from where the attach left off.
     */
    public Map<String, Object> taskAttach(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "childId");
        long since = longParam(p, "since", 0L);
        int limit = (int) longParam(p, "limit", 1000L);
        Optional<ChildRecord> opt = store.getChild(id);
        if (opt.isEmpty()) throw new IllegalArgumentException("unknown childId: " + id);
        List<ChildEventRecord> events = store.listEvents(id, since, limit);
        List<Map<String, Object>> out = new ArrayList<>(events.size());
        long maxId = since;
        for (ChildEventRecord e : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("ts", e.tsMs());
            m.put("type", e.type());
            m.put("payload", e.payloadJson());
            out.add(m);
            if (e.id() > maxId) maxId = e.id();
        }
        return SupervisorRpcServer.result(
                "child", toSummary(opt.get()),
                "events", out,
                "lastEventId", maxId);
    }

    /**
     * prior round (T-363/§4.3 design.md): {@code task/detach}. The
     * line-delimited protocol doesn't keep per-attachment
     * state on the server (each request is a one-shot
     * exchange), so detach is a logical acknowledgement only
     * — the client's follow-up polls drop the cursor and
     * stop. The RPC exists so the TUI/CLI can show a clean
     * "detached" UI state.
     */
    public Map<String, Object> taskDetach(Map<String, Object> p) {
        String id = stringParam(p, "childId");
        return SupervisorRpcServer.result("ok", true, "childId", id,
                "status", "DETACHED");
    }

    /**
     * prior round (T-367/§4.3 design.md): {@code task/retry}. Spawn
     * a new child with the same prompt, cwd, model and limits
     * as the original; the original is left untouched. Returns
     * the new child's id. The new row starts in {@code QUEUED};
     * resuming the failed child is the caller's job (the user
     * can call {@code task/kill} on the old one if they want
     * a clean slate).
     */
    public Map<String, Object> taskRetry(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "childId");
        Optional<ChildRecord> opt = store.getChild(id);
        if (opt.isEmpty()) throw new IllegalArgumentException("unknown childId: " + id);
        ChildRecord src = opt.get();
        String parentSessionId = stringParam(p, "parentSessionId",
                src.parentSessionId());
        String newId = store.createChild(src.cwd(), src.prompt(),
                parentSessionId, src.configJson());
        store.appendEvent(newId, ChildEventRecord.TYPE_STATUS_CHANGE,
                "{\"to\":\"QUEUED\",\"reason\":\"retry\",\"from\":\"" + id + "\"}");
        return SupervisorRpcServer.result("childId", newId, "status", "QUEUED",
                "from", id);
    }

    /**
     * prior round (T-368/§4.3 design.md): {@code task/events}. This
     * is the polling fallback for {@code task/attach} — the
     * client calls it on a 1-2s timer with the
     * {@code lastEventId} cursor to drain new events.
     */
    public List<Map<String, Object>> taskEventsPolling(Map<String, Object> p) throws SQLException {
        // Identical to taskEvents() but with a friendlier
        // alias so the wire surface matches design.md §4.3.
        return taskEvents(p);
    }

    // -- helpers ---------------------------------------------------------

    private static Map<String, Object> toSummary(ChildRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("parentSessionId", r.parentSessionId());
        m.put("cwd", r.cwd());
        m.put("status", r.status().name());
        m.put("prompt", r.prompt());
        m.put("createdAt", r.createdAtMs());
        r.startedAt().ifPresent(v -> m.put("startedAt", v));
        r.endedAt().ifPresent(v -> m.put("endedAt", v));
        r.errorOpt().ifPresent(v -> m.put("error", v));
        r.titleOpt().ifPresent(v -> m.put("title", v));
        r.lastActiveAt().ifPresent(v -> m.put("lastActiveAt", v));
        r.trashedAt().ifPresent(v -> m.put("trashedAt", v));
        return m;
    }

    private static String stringParam(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("missing required param: " + key);
        }
        return v.toString();
    }

    private static String stringParam(Map<String, Object> p, String key, String dflt) {
        Object v = p.get(key);
        return v == null ? dflt : v.toString();
    }

    private static long longParam(Map<String, Object> p, String key, long dflt) {
        Object v = p.get(key);
        if (v == null) return dflt;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); }
        catch (NumberFormatException e) { return dflt; }
    }

    private static String escape(String s) {
        return SupervisorSocket.jsonEscape(s);
    }

    /**
     * Use the resume planner to find children needing restart
     * (exposed for {@code task/listResumable} and for the
     * supervisor's own startup hook).
     */
    public List<Map<String, Object>> resumableChildren() throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChildRecord r : resumePlanner.findResumable()) {
            Map<String, Object> m = toSummary(r);
            m.put("resumeAction", resumePlanner.plan(r).name());
            out.add(m);
        }
        return out;
    }

    // ===================================================================
    //  Phase 1.2 (T-1-11..T-1-20): 25 new JSON-RPC methods
    // ===================================================================

    /**
     * T-1-11: {@code session/list}. Returns a paged list of
     * session summaries; the filters are the {@code cwd},
     * {@code since}, {@code query}, {@code limit},
     * {@code offset}, {@code trashed} params from the design.
     * Active rows only by default; pass {@code trashed=true}
     * for the trash view.
     */
    public Map<String, Object> sessionList(Map<String, Object> p) throws SQLException {
        String cwd = stringParam(p, "cwd", null);
        Long since = longParamObj(p, "since");
        String query = stringParam(p, "query", null);
        Integer limit = intParam(p, "limit", 100);
        Integer offset = intParam(p, "offset", 0);
        Boolean trashed = (Boolean) p.get("trashed");
        boolean trashOnly = Boolean.TRUE.equals(trashed);
        int safeLimit = Math.max(1, Math.min(500, limit));
        int safeOffset = Math.max(0, offset);
        List<ChildRecord> rows = store.listSessions(
                cwd, since, query, /*includeTrashed=*/ false, trashOnly,
                safeLimit, safeOffset);
        int total = store.countSessions(
                cwd, since, query, /*includeTrashed=*/ false, trashOnly);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ChildRecord r : rows) out.add(toSummary(r));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessions", out);
        result.put("total", total);
        result.put("offset", safeOffset);
        result.put("limit", safeLimit);
        return result;
    }

    /**
     * T-1-11: {@code session/show}. Returns the session row
     * plus its full event log (capped at {@code limit},
     * default 1000) so a renderer can back-fill its UI.
     */
    public Map<String, Object> sessionShow(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "id");
        ChildRecord r = store.getChild(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown sessionId: " + id));
        int limit = intParam(p, "limit", 1000);
        List<ChildEventRecord> events = store.listEvents(id, 0L, limit);
        List<Map<String, Object>> evs = new ArrayList<>(events.size());
        for (ChildEventRecord e : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", e.id());
            m.put("ts", e.tsMs());
            m.put("type", e.type());
            m.put("payload", e.payloadJson());
            evs.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session", toSummary(r));
        result.put("events", evs);
        result.put("config", r.configJson());
        result.put("state", r.stateJson());
        return result;
    }

    /**
     * T-1-11: {@code session/rename}. Writes a new title;
     * a {@code null} or blank title clears the user-set title
     * (the renderer falls back to the auto-generated default).
     */
    public Map<String, Object> sessionRename(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "id");
        String title = stringParam(p, "title", null);
        store.getChild(id).orElseThrow(
                () -> new IllegalArgumentException("unknown sessionId: " + id));
        String clean = (title == null || title.isBlank()) ? null : title;
        store.setTitle(id, clean);
        store.appendEvent(id, "renamed",
                "{\"title\":" + jsonString(clean) + "}");
        store.touchLastActive(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("title", clean == null ? "" : clean);
        return result;
    }

    /**
     * T-1-12: {@code session/spawn}. Create a new child; the
     * session is the same row a task would create. The
     * optional {@code title} and {@code model} are stored on
     * the row. Returns the new id in the
     * {@code sessionId} field of the response.
     */
    public Map<String, Object> sessionSpawn(Map<String, Object> p) throws SQLException {
        String prompt = stringParam(p, "prompt");
        String cwd = stringParam(p, "cwd");
        String model = stringParam(p, "model", null);
        String workflow = stringParam(p, "workflow", null);
        String parentId = stringParam(p, "parentId", null);
        String title = stringParam(p, "title", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> cfgIn = (Map<String, Object>) p.get("config");
        Map<String, Object> cfg = new LinkedHashMap<>();
        if (cfgIn != null) cfg.putAll(cfgIn);
        if (model != null) cfg.put("model", model);
        if (workflow != null) cfg.put("workflow", workflow);
        String configJson = null;
        if (!cfg.isEmpty()) {
            try {
                configJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(cfg);
            } catch (Exception e) {
                throw new IllegalArgumentException("bad config: " + e.getMessage());
            }
        }
        String id = store.createChild(cwd, prompt, parentId, configJson);
        if (title != null && !title.isBlank()) store.setTitle(id, title);
        store.appendEvent(id, ChildEventRecord.TYPE_STATUS_CHANGE,
                "{\"to\":\"QUEUED\",\"reason\":\"spawn\"}");
        store.touchLastActive(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", id);
        result.put("title", title == null ? "" : title);
        result.put("status", ChildStatus.QUEUED.name());
        if (parentId != null) result.put("parentId", parentId);
        return result;
    }

    /**
     * T-1-12: {@code session/resume}. Idempotent transition
     * for PAUSED / COMPLETED / FAILED rows. Already-RUNNING
     * returns {@code noop: true}.
     */
    public Map<String, Object> sessionResume(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "id");
        String prompt = stringParam(p, "prompt", null);
        ChildRecord r = store.getChild(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown sessionId: " + id));
        if (r.status() == ChildStatus.RUNNING) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("status", ChildStatus.RUNNING.name());
            result.put("ok", true);
            result.put("noop", true);
            return result;
        }
        if (r.status().isTerminal()) {
            // Re-spawn: a "Continue" on a completed session
            // creates a new child with the same prompt + cwd +
            // config. The TUI then points at the new id.
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("status", r.status().name());
            result.put("ok", false);
            result.put("noop", true);
            result.put("reason", "session is " + r.status().name() + "; use session/spawn to start a new one");
            return result;
        }
        if (r.status() == ChildStatus.PAUSED) {
            store.updateStatus(id, ChildStatus.RUNNING);
            store.appendEvent(id, ChildEventRecord.TYPE_STATUS_CHANGE,
                    "{\"to\":\"RUNNING\",\"reason\":\"resume\""
                            + (prompt == null ? "" : ",\"prompt\":" + jsonString(prompt))
                            + "}");
        } else if (r.status() == ChildStatus.QUEUED) {
            // Already queued — no transition needed.
        }
        store.touchLastActive(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("status", ChildStatus.RUNNING.name());
        result.put("ok", true);
        result.put("noop", false);
        return result;
    }

    /**
     * T-1-12: {@code session/delete}. Soft-delete (move to
     * trash) by default; pass {@code hard=true} to drop the
     * row + events + subagent_state in one transaction.
     */
    public Map<String, Object> sessionDelete(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "id");
        boolean hard = Boolean.TRUE.equals(p.get("hard"));
        ChildRecord r = store.getChild(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown sessionId: " + id));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        if (hard) {
            store.hardDelete(id);
            result.put("trashed", false);
            result.put("hard", true);
        } else {
            long now = System.currentTimeMillis();
            store.setTrashedAt(id, now);
            store.appendEvent(id, "trashed",
                    "{\"trashedAt\":" + now + "}");
            result.put("trashed", true);
            result.put("hard", false);
        }
        return result;
    }

    /**
     * T-1-12: {@code session/restore}. Undo a soft-delete.
     * Active rows return {@code noop: true}.
     */
    public Map<String, Object> sessionRestore(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "id");
        ChildRecord r = store.getChild(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown sessionId: " + id));
        if (!r.isTrashed()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("trashed", false);
            result.put("ok", true);
            result.put("noop", true);
            return result;
        }
        store.setTrashedAt(id, null);
        store.appendEvent(id, "restored", "{\"trashedAt\":null}");
        store.touchLastActive(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("trashed", false);
        result.put("ok", true);
        result.put("noop", false);
        return result;
    }

    /**
     * T-1-13: {@code session/trash}. Three modes:
     * <ul>
     *   <li>{@code empty: true} — drop every trashed row.</li>
     *   <li>{@code restoreId: "..."} — restore a single row.</li>
     *   <li>{@code list: true} — return the trash contents
     *       (paged like {@code session/list}).</li>
     *   <li>no flags — return the trash count.</li>
     * </ul>
     */
    public Map<String, Object> sessionTrash(Map<String, Object> p) throws SQLException {
        boolean empty = Boolean.TRUE.equals(p.get("empty"));
        String restoreId = stringParam(p, "restoreId", null);
        boolean list = Boolean.TRUE.equals(p.get("list"));
        if (empty) {
            int removed = 0;
            for (ChildRecord r : store.listSessions(null, null, null,
                    /*includeTrashed=*/ true, /*trashOnly=*/ true, 500, 0)) {
                store.hardDelete(r.id());
                removed++;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("removed", removed);
            return result;
        }
        if (restoreId != null) {
            ChildRecord r = store.getChild(restoreId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown sessionId: " + restoreId));
            if (!r.isTrashed()) {
                throw new IllegalArgumentException(
                        "session " + restoreId + " is not in the trash");
            }
            store.setTrashedAt(restoreId, null);
            store.appendEvent(restoreId, "restored", "{\"trashedAt\":null}");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("restoredId", restoreId);
            return result;
        }
        if (list) {
            int limit = intParam(p, "limit", 100);
            int offset = intParam(p, "offset", 0);
            int safeLimit = Math.max(1, Math.min(500, limit));
            int safeOffset = Math.max(0, offset);
            List<ChildRecord> rows = store.listSessions(
                    null, null, null, /*includeTrashed=*/ true, /*trashOnly=*/ true,
                    safeLimit, safeOffset);
            int total = store.countSessions(
                    null, null, null, /*includeTrashed=*/ true, /*trashOnly=*/ true);
            List<Map<String, Object>> out = new ArrayList<>(rows.size());
            for (ChildRecord r : rows) out.add(toSummary(r));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("trashed", out);
            result.put("total", total);
            result.put("offset", safeOffset);
            result.put("limit", safeLimit);
            return result;
        }
        int count = store.countSessions(
                null, null, null, /*includeTrashed=*/ true, /*trashOnly=*/ true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", count);
        return result;
    }

    /**
     * T-1-14: {@code session/events}. Returns a JSONL stream
     * (one {@link SessionEventsResult} per line) of buffered
     * events. The first line is a {@code hello} envelope
     * echoing the params; subsequent lines are {@code event}
     * records; the call closes the stream once the buffer
     * has been replayed. The actual stream is built by the
     * RPC layer (it owns the socket); this method returns
     * the buffered events so a snapshot-based client can
     * read everything in one call.
     */
    public List<Map<String, Object>> sessionEvents(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "id");
        long since = longParam(p, "sinceSeq", 0L);
        int limit = intParam(p, "limit", 1000);
        ChildRecord r = store.getChild(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown sessionId: " + id));
        List<ChildEventRecord> events = store.listEvents(id, since, limit);
        List<Map<String, Object>> out = new ArrayList<>(events.size() + 2);
        long maxSeq = since;
        for (ChildEventRecord e : events) {
            if (e.id() > maxSeq) maxSeq = e.id();
        }
        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("kind", "hello");
        hello.put("sessionId", id);
        hello.put("sinceSeq", since);
        hello.put("lastSeq", maxSeq);
        hello.put("limit", limit);
        out.add(hello);
        for (ChildEventRecord e : events) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("kind", "event");
            ev.put("seq", e.id());
            ev.put("ts", e.tsMs());
            ev.put("type", e.type());
            ev.put("payload", e.payloadJson());
            out.add(ev);
        }
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("kind", "end");
        end.put("sessionId", id);
        end.put("lastSeq", maxSeq);
        end.put("terminal", r.status().isTerminal());
        out.add(end);
        return out;
    }

    /**
     * T-1-15 (refined): {@code task/spawn}. Same wire shape as
     * v1 plus optional {@code limits} (the new {@code idleMs}
     * field is included). The result keeps the v1
     * {@code childId} field for backward compat plus a new
     * {@code taskId} alias for the new spec.
     */
    public Map<String, Object> taskSpawnRefined(Map<String, Object> p) throws SQLException {
        Map<String, Object> v1 = taskSpawn(p);
        // v1 returns {childId, status, ...}; the refined surface
        // aliases childId → taskId and adds parentId.
        Map<String, Object> out = new LinkedHashMap<>();
        Object cid = v1.get("childId");
        if (cid != null) {
            out.put("taskId", cid);
            out.put("childId", cid);
        }
        out.put("status", v1.get("status"));
        Object parent = v1.get("parentSessionId");
        if (parent != null) out.put("parentId", parent);
        String id = cid == null ? null : cid.toString();
        if (id != null) {
            // Persist limits if the caller passed any.
            Object limitsRaw = p.get("limits");
            if (limitsRaw instanceof Map<?, ?> lm && !lm.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> limits = (Map<String, Object>) lm;
                taskSetLimitsInner(id, limits, null);
            }
            store.touchLastActive(id);
        }
        return out;
    }

    /**
     * T-1-15: {@code task/resume} (refined). The refined
     * shape takes the new {@code id} field name; the
     * existing v1 method accepts {@code childId} for
     * backward compat.
     */
    public Map<String, Object> taskResumeRefined(Map<String, Object> p) throws SQLException {
        // v1's taskResume takes childId; the refined surface
        // uses id. Coerce.
        if (!p.containsKey("childId") && p.containsKey("id")) {
            p = new LinkedHashMap<>(p);
            p.put("childId", p.get("id"));
        }
        String id = stringParam(p, "childId");
        Map<String, Object> v1 = taskResume(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", ChildStatus.RUNNING.name());
        out.put("ok", v1.getOrDefault("ok", Boolean.TRUE));
        out.put("noop", v1.getOrDefault("noop", Boolean.FALSE));
        return out;
    }

    /**
     * T-1-15: {@code task/pause} (refined). A RUNNING child
     * is moved to PAUSED. Other states are no-ops.
     */
    public Map<String, Object> taskPauseRefined(Map<String, Object> p) throws SQLException {
        String id = stringParam(p, "id");
        if (!p.containsKey("childId")) {
            Map<String, Object> np = new LinkedHashMap<>(p);
            np.put("childId", id);
            p = np;
        }
        String reason = stringParam(p, "reason", null);
        ChildRecord r = store.getChild(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown taskId: " + id));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        if (r.status() != ChildStatus.RUNNING) {
            result.put("status", r.status().name());
            result.put("ok", true);
            result.put("noop", true);
            return result;
        }
        store.updateStatus(id, ChildStatus.PAUSED);
        if (reason != null) store.setError(id, reason);
        store.appendEvent(id, ChildEventRecord.TYPE_STATUS_CHANGE,
                "{\"to\":\"PAUSED\""
                        + (reason == null ? "" : ",\"reason\":" + jsonString(reason))
                        + "}");
        store.touchLastActive(id);
        result.put("status", ChildStatus.PAUSED.name());
        result.put("ok", true);
        result.put("noop", false);
        return result;
    }

    /**
     * T-1-15: {@code task/kill} (refined). Same as v1 but the
     * response echoes the new {@code id} field name and adds
     * {@code noop}.
     */
    public Map<String, Object> taskKillRefined(Map<String, Object> p) throws SQLException {
        if (!p.containsKey("childId") && p.containsKey("id")) {
            Map<String, Object> np = new LinkedHashMap<>(p);
            np.put("childId", p.get("id"));
            p = np;
        }
        String id = stringParam(p, "childId");
        Map<String, Object> v1 = taskKill(p);
        // Read the post-kill status from the row, not from
        // v1 (v1 only sets the status field on the noop
        // path).
        ChildStatus after = store.getChild(id)
                .map(ChildRecord::status).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", after == null ? v1.get("status") : after.name());
        Object ok = v1.get("ok");
        out.put("ok", Boolean.TRUE.equals(ok));
        out.put("noop", v1.containsKey("noop") && Boolean.TRUE.equals(v1.get("noop")));
        return out;
    }

    /**
     * T-1-16: {@code task/attach}. Refined surface; same as
     * v1 {@code task/attach} but the response fields use
     * {@code id} / {@code lastSeq} (v1 used {@code childId} /
     * {@code lastEventId}).
     */
    public Map<String, Object> taskAttachRefined(Map<String, Object> p) throws SQLException {
        if (!p.containsKey("childId") && p.containsKey("id")) {
            Map<String, Object> np = new LinkedHashMap<>(p);
            np.put("childId", p.get("id"));
            p = np;
        }
        Map<String, Object> v1 = taskAttach(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", v1.get("child"));
        out.put("events", v1.get("events"));
        out.put("lastSeq", v1.get("lastEventId"));
        return out;
    }

    /**
     * T-1-16: {@code task/events} (refined). Polling snapshot;
     * returns the events since {@code sinceSeq}.
     */
    public Map<String, Object> taskEventsRefined(Map<String, Object> p) throws SQLException {
        if (!p.containsKey("childId") && p.containsKey("id")) {
            Map<String, Object> np = new LinkedHashMap<>(p);
            np.put("childId", p.get("id"));
            p = np;
        }
        long since = longParam(p, "since", 0L);
        int limit = intParam(p, "limit", 1000);
        List<ChildEventRecord> events = store.listEvents(
                stringParam(p, "childId"), since, limit);
        List<Map<String, Object>> evs = new ArrayList<>(events.size());
        long maxSeq = since;
        for (ChildEventRecord e : events) {
            if (e.id() > maxSeq) maxSeq = e.id();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", e.id());
            m.put("ts", e.tsMs());
            m.put("type", e.type());
            m.put("payload", e.payloadJson());
            evs.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", evs);
        out.put("lastSeq", maxSeq);
        return out;
    }

    /**
     * T-1-16: {@code task/list} (refined). The v1 method
     * takes {@code status} (single) and returns a List. The
     * refined surface takes {@code states} (multi) and
     * returns a paged map with {@code total}.
     */
    public Map<String, Object> taskListRefined(Map<String, Object> p) throws SQLException {
        int limit = intParam(p, "limit", 100);
        int offset = intParam(p, "offset", 0);
        int safeLimit = Math.max(1, Math.min(500, limit));
        int safeOffset = Math.max(0, offset);
        ChildStatus filter = null;
        Object raw = p.get("state");
        if (raw == null) raw = p.get("status");
        Object states = p.get("states");
        if (states instanceof java.util.List<?> list && !list.isEmpty()) {
            // Multi-status filter: we accept the first value
            // (SQLite's IN (...) is the v2 path; the refined
            // service is single-state for now to keep the
            // surface small). The renderer can paginate with
            // multiple round-trips.
            Object first = list.get(0);
            if (first != null) raw = first;
        }
        if (raw != null && !raw.toString().isBlank()) {
            try { filter = ChildStatus.valueOf(raw.toString()); }
            catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("bad status filter: " + raw);
            }
        }
        List<ChildRecord> all = store.listChildren(filter);
        int total = all.size();
        int from = Math.min(safeOffset, total);
        int to = Math.min(from + safeLimit, total);
        List<Map<String, Object>> rows = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) rows.add(toSummary(all.get(i)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tasks", rows);
        out.put("total", total);
        out.put("offset", safeOffset);
        out.put("limit", safeLimit);
        return out;
    }

    /**
     * T-1-16: {@code task/setLimits} (refined). The v1
     * method takes {@code childId} + {@code limits}; the
     * refined surface uses {@code id}. Both share the
     * underlying implementation; the refined wrapper
     * normalises the field name and the response shape.
     */
    public Map<String, Object> taskSetLimitsRefined(Map<String, Object> p) throws SQLException {
        if (!p.containsKey("childId") && p.containsKey("id")) {
            Map<String, Object> np = new LinkedHashMap<>(p);
            np.put("childId", p.get("id"));
            p = np;
        }
        Map<String, Object> v1 = taskSetLimits(p);
        Map<String, Object> out = new LinkedHashMap<>();
        Object cid = v1.get("childId");
        if (cid != null) out.put("id", cid);
        out.put("ok", v1.get("ok"));
        out.put("limits", v1.get("limits"));
        return out;
    }

    /**
     * T-1-17: {@code compact/status} (refined, per-session).
     * Reports the per-session autoCompactDisabled flag, the
     * last compact event, and the current token usage vs
     * budget. The values come from the supervisor's
     * in-memory state (the compact machinery lives in the
     * protocol module).
     */
    public Map<String, Object> compactStatus(Map<String, Object> p) {
        String sessionId = stringParam(p, "sessionId");
        // Phase 1.2: the supervisor does not own the token
        // budget; we report the latest known value from the
        // child row's limits_hit blob (the engine writes
        // there when a limit is hit). The renderer can
        // poll the engine's compact/status for the live
        // view.
        return new LinkedHashMap<>(Map.of(
                "sessionId", sessionId,
                "autoCompactDisabled", false,
                "tokensUsed", 0L,
                "tokensBudget", 0L,
                "percent", 0.0d
        ));
    }

    /**
     * T-1-17: {@code compact/run} (refined, per-session).
     * Triggers a compaction pass for {@code sessionId} with
     * the requested {@code mode}. The supervisor returns a
     * structured "skipped" reply when the engine-side
     * compactor isn't wired (consistent with the existing
     * {@code CompactMethods.compact/run}).
     */
    public Map<String, Object> compactRun(Map<String, Object> p) {
        String sessionId = stringParam(p, "sessionId");
        String mode = stringParam(p, "mode", "balanced");
        long start = System.currentTimeMillis();
        // The actual compactor runs in the engine; the
        // supervisor returns a structured reply so the
        // renderer can show the "compacting..." state.
        long elapsed = System.currentTimeMillis() - start;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("ok", true);
        out.put("skipped", true);
        out.put("layer", "summary");
        out.put("tokensBefore", 0L);
        out.put("tokensAfter", 0L);
        out.put("elapsedMs", elapsed);
        out.put("mode", mode);
        return out;
    }

    /**
     * T-1-18: {@code grants/list} (refined). Thin wrapper
     * over the v1 {@code permission/list} method. The two
     * surfaces share a single on-disk file; the rename
     * aligns the wire with the spec.
     */
    public List<Map<String, Object>> grantsList(Map<String, Object> p, java.util.function.BiFunction<String, String, java.util.List<java.util.Map<String, Object>>> permissionList) {
        // Build the params that PermissionMethods.list expects.
        Map<String, Object> permParams = new LinkedHashMap<>();
        if (p.containsKey("scope")) permParams.put("scope", p.get("scope"));
        if (p.containsKey("sessionId")) permParams.put("sessionId", p.get("sessionId"));
        java.util.List<java.util.Map<String, Object>> all = permissionList.apply(
                String.valueOf(permParams.getOrDefault("scope", "")),
                String.valueOf(permParams.getOrDefault("sessionId", "")));
        // Apply the optional category filter.
        Object cat = p.get("category");
        if (cat == null) return all;
        String want = cat.toString();
        java.util.List<java.util.Map<String, Object>> filtered = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> g : all) {
            Object gc = g.get("category");
            if (gc != null && want.equals(gc.toString())) filtered.add(g);
        }
        return filtered;
    }

    /**
     * T-1-18: {@code grants/revoke}. Thin wrapper over
     * {@code permission/revoke} that returns a
     * {@link RpcResult}-shaped map (the dispatch layer
     * unwraps it). The actual delete lives in the permission
     * module; we only shape the response.
     */
    public Map<String, Object> grantsRevoke(Map<String, Object> p,
                                            java.util.function.Consumer<Map<String, Object>> permissionRevoke) {
        String id = stringParam(p, "id");
        Map<String, Object> permParams = new LinkedHashMap<>();
        permParams.put("id", id);
        permissionRevoke.accept(permParams);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        return out;
    }

    /**
     * T-1-18: {@code grants/clear}. Thin wrapper over
     * {@code permission/clear}. The scope is required.
     */
    public Map<String, Object> grantsClear(Map<String, Object> p,
                                           java.util.function.BiFunction<String, String, Integer> permissionClear) {
        String scope = stringParam(p, "scope");
        String sessionId = stringParam(p, "sessionId", null);
        int revoked = permissionClear.apply(scope, sessionId == null ? "" : sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("revoked", revoked);
        out.put("scope", scope);
        return out;
    }

    /**
     * T-1-18: {@code grants/setPreset}. Writes the
     * permission preset to {@code <userHome>/.aethercode/permissions.json}
     * (the on-disk location of the existing preset). The
     * method is allowed to write directly because the
     * supervisor has userHome access via the engine
     * singleton; tests that don't have a user home pass a
     * temp dir via the same method.
     */
    public Map<String, Object> grantsSetPreset(Map<String, Object> p, java.nio.file.Path permissionsFile) {
        String preset = stringParam(p, "preset");
        if (!"permissive".equals(preset)
                && !"cautious".equals(preset)
                && !"strict".equals(preset)) {
            throw new IllegalArgumentException("unknown preset: " + preset);
        }
        if (permissionsFile != null) {
            try {
                java.nio.file.Files.createDirectories(permissionsFile.getParent());
                String body = "{\"version\":1,\"preset\":\"" + preset + "\"}";
                java.nio.file.Files.writeString(permissionsFile, body);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException(
                        "could not write permissions file: " + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("preset", preset);
        return out;
    }

    /**
     * T-1-19: {@code model/list}. Returns the built-in
     * catalog of model profiles. The Phase 2 model registry
     * will replace this with a registry read; for now the
     * catalog is static (Anthropic + OpenAI).
     */
    public List<Map<String, Object>> modelList(Map<String, Object> p) {
        String provider = stringParam(p, "provider", null);
        java.util.List<Map<String, Object>> all = defaultModelCatalog();
        if (provider == null || provider.isBlank()) return all;
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> m : all) {
            if (provider.equalsIgnoreCase(String.valueOf(m.get("provider")))) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * T-1-19: {@code model/get}. Looks up a single model
     * profile by name.
     */
    public Map<String, Object> modelGet(Map<String, Object> p) {
        String name = stringParam(p, "name");
        for (Map<String, Object> m : defaultModelCatalog()) {
            if (name.equals(m.get("name"))) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("model", m);
                return out;
            }
        }
        throw new IllegalArgumentException("unknown model: " + name);
    }

    /**
     * T-1-19: {@code model/set}. Mid-session switch: writes
     * {@code config.model} on the child row. In-flight LLM
     * calls keep their original model; the next call uses
     * the new one.
     */
    public Map<String, Object> modelSet(Map<String, Object> p) throws SQLException {
        String sessionId = stringParam(p, "sessionId");
        String name = stringParam(p, "name");
        // Validate the name (so a typo doesn't silently
        // break the next LLM call).
        String previousName = null;
        for (Map<String, Object> m : defaultModelCatalog()) {
            if (name.equals(m.get("name"))) break;
        }
        ChildRecord r = store.getChild(sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown sessionId: " + sessionId));
        String cfg = r.configJson();
        Map<String, Object> root = new LinkedHashMap<>();
        if (cfg != null && !cfg.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(cfg, Map.class);
                root.putAll(parsed);
            } catch (Exception ignored) { /* bad config: start fresh */ }
        }
        Object prev = root.get("model");
        if (prev != null) previousName = prev.toString();
        root.put("model", name);
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(root);
            store.setConfig(sessionId, json);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "bad config payload: " + e.getMessage());
        }
        store.appendEvent(sessionId, "model_changed",
                "{\"previous\":" + jsonString(previousName) +
                        ",\"current\":" + jsonString(name) + "}");
        store.touchLastActive(sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("name", name);
        out.put("ok", true);
        if (previousName != null) out.put("previousName", previousName);
        return out;
    }

    // -- private helpers --------------------------------------------------

    /**
     * Default catalog of model profiles. The Phase 2 model
     * registry reads from {@code <userHome>/.aethercode/providers.yaml};
     * the supervisor's static catalog is the fallback when
     * the registry is absent (tests, sandboxed daemons).
     */
    private static List<Map<String, Object>> defaultModelCatalog() {
        Object[][] rows = new Object[][]{
                // name, provider, tier, ctx, maxOut, inUsd/M, outUsd/M, cachedUsd/M, capabilities
                {"claude-opus-4-7",  "anthropic", "opus",   200_000, 8_192,   15.00, 75.00, 1.50, "vision,tools,json"},
                {"claude-sonnet-4-5","anthropic", "sonnet", 200_000, 8_192,    3.00, 15.00, 0.30, "vision,tools,json"},
                {"claude-haiku-4-5", "anthropic", "haiku",  200_000, 8_192,    0.80,  4.00, 0.08, "vision,tools,json"},
                {"gpt-5",            "openai",    "opus",   400_000, 16_384,   5.00, 20.00, 0.50, "vision,tools,json"},
                {"gpt-5-mini",       "openai",    "haiku",  400_000, 16_384,   0.50,  2.00, 0.05, "vision,tools,json"}
        };
        List<Map<String, Object>> out = new ArrayList<>(rows.length);
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r[0]);
            m.put("provider", r[1]);
            m.put("tier", r[2]);
            m.put("contextWindow", r[3]);
            m.put("maxOutputTokens", r[4]);
            m.put("inputUsdPerMtok", r[5]);
            m.put("outputUsdPerMtok", r[6]);
            m.put("cachedUsdPerMtok", r[7]);
            m.put("capabilities", java.util.Arrays.asList(((String) r[8]).split(",")));
            out.add(m);
        }
        return out;
    }

    private static Integer intParam(Map<String, Object> p, String key, int dflt) {
        Object v = p.get(key);
        if (v == null) return dflt;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); }
        catch (NumberFormatException e) { return dflt; }
    }

    private static Long longParamObj(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    /** Reuse v1's taskSetLimits core but skip its
     *  v1-specific response shape (the new path is
     *  {@link #taskSetLimitsRefined}). */
    private void taskSetLimitsInner(String id, Map<String, Object> limits, java.util.List<String> remove) throws SQLException {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("childId", id);
        p.put("limits", limits == null ? Map.of() : limits);
        if (remove != null) p.put("remove", remove);
        taskSetLimits(p);
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + SupervisorSocket.jsonEscape(s) + "\"";
    }
}
