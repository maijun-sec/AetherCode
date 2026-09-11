package org.aethercode.tasks.supervisor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * prior round (T-312/§4.3 design.md): JSON-RPC 2.0 over the supervisor
 * socket. Self-contained: uses the local
 * {@link JsonRpcEnvelope} so aethercode-tasks does not have to
 * depend on aethercode-protocol (which would cycle through
 * aethercode-sdk).
 *
 * <p>Registered methods (matches design.md §4.3 / spec.md §4.2):
 * <ul>
 *   <li>{@code task/spawn} — {prompt, cwd, model, limits?} → {childId}</li>
 *   <li>{@code task/list} — {status?} → [{id, status, prompt, cwd, ...}]</li>
 *   <li>{@code task/get}  — {childId} → child record (incl. last events)</li>
 *   <li>{@code task/attach} — {childId, since?, limit?} → {child, events, lastEventId}</li>
 *   <li>{@code task/detach} — {childId} → {ok, status: "DETACHED"}</li>
 *   <li>{@code task/events} — {childId, since?} → replay events (polling fallback)</li>
 *   <li>{@code task/kill} — {childId, reason?} → {ok}</li>
 *   <li>{@code task/await} — {childId, timeoutMs?} → {status, result?}</li>
 *   <li>{@code task/resume} — {childId} → {ok}</li>
 *   <li>{@code task/retry} — {childId} → {childId, status, from}  (new child)</li>
 *   <li>{@code task/setLimits} — {childId, limits, remove?} → {ok, limits}</li>
 *   <li>{@code task/appendEvent} — {childId, type, payload} → {eventId}</li>
 *   <li>{@code task/ping} — {} → {pong, ts}</li>
 *   <li>{@code task/listResumable} — {} → [{id, status, resumeAction, ...}]</li>
 * </ul>
 */
public final class SupervisorRpcServer {

    private static final Logger LOG = LoggerFactory.getLogger(SupervisorRpcServer.class);

    private final SupervisorService service;
    private final ObjectMapper mapper;
    /** Phase 1.2 (T-1-18): optional grants/* handler. When
     *  null, the {@code grants/*} methods reply with
     *  NOT_IMPLEMENTED (the supervisor doesn't own the
     *  on-disk grants; that's the permission module's job). */
    private org.aethercode.tasks.rpc.GrantHandlers grants;
    /** Phase 2.1 (T-2-08..T-2-12): optional workflow/* handler.
     *  When null the {@code workflow/*} methods reply with
     *  NOT_IMPLEMENTED. The handler is owned by Java-B and
     *  delegates to aethercode-workflows. */
    private org.aethercode.tasks.rpc.workflow.WorkflowHandlers workflow;

    public SupervisorRpcServer(SupervisorService service) {
        this.service = service;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public ObjectMapper mapper() { return mapper; }

    /** Wire the four {@code grants/*} methods to a
     *  {@link org.aethercode.tasks.rpc.GrantHandlers}.
     *  Idempotent: re-registering replaces the prior binding. */
    public void setGrants(org.aethercode.tasks.rpc.GrantHandlers grants) {
        this.grants = grants;
    }

    public org.aethercode.tasks.rpc.GrantHandlers grants() { return grants; }

    /** Wire the five {@code workflow/*} methods to a
     *  {@link org.aethercode.tasks.rpc.workflow.WorkflowHandlers}.
     *  Idempotent: re-registering replaces the prior binding. */
    public void setWorkflow(org.aethercode.tasks.rpc.workflow.WorkflowHandlers workflow) {
        this.workflow = workflow;
    }

    public org.aethercode.tasks.rpc.workflow.WorkflowHandlers workflow() { return workflow; }

    /** Handle one inbound JSON line; return the reply line (or null on notification). */
    public String handleLine(String line) {
        if (line == null || line.isBlank()) return null;
        Object msg;
        try {
            msg = JsonRpcEnvelope.decode(mapper, line);
        } catch (Exception e) {
            return encode(JsonRpcEnvelope.Response.err(null,
                    JsonRpcEnvelope.Codes.PARSE_ERROR, e.getMessage()));
        }
        if (msg instanceof JsonRpcEnvelope.Response) {
            return null; // peer reply, ignore
        }
        if (msg instanceof JsonRpcEnvelope.Request req) {
            try {
                Object result = invoke(req.method(), req.params());
                return encode(JsonRpcEnvelope.Response.ok(req.id(), result));
            } catch (IllegalArgumentException iae) {
                return encode(JsonRpcEnvelope.Response.err(req.id(),
                        JsonRpcEnvelope.Codes.INVALID_PARAMS, iae.getMessage()));
            } catch (Exception e) {
                LOG.error("rpc {} failed: {}", req.method(), e.getMessage(), e);
                return encode(JsonRpcEnvelope.Response.err(req.id(),
                        JsonRpcEnvelope.Codes.INTERNAL_ERROR, e.getMessage()));
            }
        }
        if (msg instanceof JsonRpcEnvelope.Notification notif) {
            try { invoke(notif.method(), notif.params()); }
            catch (Exception e) { LOG.warn("notif {} failed: {}", notif.method(), e.getMessage()); }
            return null;
        }
        return encode(JsonRpcEnvelope.Response.err(null,
                JsonRpcEnvelope.Codes.INVALID_REQUEST, "unrecognised envelope"));
    }

    private Object invoke(String method, Object params) throws Exception {
        Map<String, Object> p = coerceParams(params);
        return switch (method) {
            case "task/spawn"        -> service.taskSpawn(p);
            case "task/list"         -> service.taskList(p);
            case "task/get"          -> service.taskGet(p);
            case "task/events"       -> service.taskEvents(p);
            case "task/attach"       -> service.taskAttach(p);
            case "task/detach"       -> service.taskDetach(p);
            case "task/retry"        -> service.taskRetry(p);
            case "task/setLimits"    -> service.taskSetLimits(p);
            case "task/kill"         -> service.taskKill(p);
            case "task/await"        -> service.taskAwait(p);
            case "task/resume"       -> service.taskResume(p);
            case "task/appendEvent"  -> service.taskAppendEvent(p);
            case "task/ping"         -> Map.of("pong", true, "ts", System.currentTimeMillis());
            case "task/listResumable" -> service.resumableChildren();
            // Phase 1.2 (T-1-11..T-1-20): 25 new JSON-RPC methods
            // on the session/*, task/*, compact/*, grants/*,
            // model/* and workflow/* surfaces.
            case "session/list"      -> service.sessionList(p);
            case "session/show"      -> service.sessionShow(p);
            case "session/rename"    -> service.sessionRename(p);
            case "session/spawn"     -> service.sessionSpawn(p);
            case "session/resume"    -> service.sessionResume(p);
            case "session/delete"    -> service.sessionDelete(p);
            case "session/restore"   -> service.sessionRestore(p);
            case "session/trash"     -> service.sessionTrash(p);
            case "session/events"    -> service.sessionEvents(p);
            // The new "refined" task/* methods share the wire
            // namespace with the v1 ones. The dispatch layer
            // routes based on the exact method name; the
            // refined variants are exposed under the new spec
            // names (e.g. task/spawn.v2, task/resume.v2) so
            // v1 callers aren't disturbed. Java-A's Phase 2
            // work can fold them in.
            case "task/spawn.v2"     -> service.taskSpawnRefined(p);
            case "task/resume.v2"    -> service.taskResumeRefined(p);
            case "task/pause.v2"     -> service.taskPauseRefined(p);
            case "task/kill.v2"      -> service.taskKillRefined(p);
            case "task/attach.v2"    -> service.taskAttachRefined(p);
            case "task/events.v2"    -> service.taskEventsRefined(p);
            case "task/list.v2"      -> service.taskListRefined(p);
            case "task/setLimits.v2" -> service.taskSetLimitsRefined(p);
            case "compact/status"    -> service.compactStatus(p);
            case "compact/run"       -> service.compactRun(p);
            // grants/* are wired through a delegation callback
            // in the dispatch wrapper because the implementation
            // lives in the permission module. See
            // SupervisorRpcServer.setGrants(...).
            case "grants/list"      -> grants != null ? grants.list(p) : notImplemented("grants/list");
            case "grants/revoke"    -> grants != null ? grants.revoke(p) : notImplemented("grants/revoke");
            case "grants/clear"     -> grants != null ? grants.clear(p) : notImplemented("grants/clear");
            case "grants/setPreset" -> grants != null ? grants.setPreset(p) : notImplemented("grants/setPreset");
            // model/* are static for Phase 1.2; the Phase 2
            // model registry replaces them.
            case "model/list"        -> service.modelList(p);
            case "model/get"         -> service.modelGet(p);
            case "model/set"         -> service.modelSet(p);
            // workflow/* are stubs in Phase 1.2; they return
            // {ok: false, error: {name: "NOT_IMPLEMENTED"}}.
            // Phase 2.1 (T-2-08..T-2-12): Java-B wires the
            // aethercode-workflows service via setWorkflow().
            case "workflow/list"     -> workflow != null ? workflow.list(p)    : notImplemented("workflow/list");
            case "workflow/show"     -> workflow != null ? workflow.show(p)    : notImplemented("workflow/show");
            case "workflow/run"      -> workflow != null ? workflow.run(p)     : notImplemented("workflow/run");
            case "workflow/upsert"   -> workflow != null ? workflow.upsert(p)  : notImplemented("workflow/upsert");
            case "workflow/delete"   -> workflow != null ? workflow.delete(p)  : notImplemented("workflow/delete");
            default -> throw new IllegalArgumentException("unknown method: " + method);
        };
    }

    /**
     * Phase 1.2 (T-1-20): structured NOT_IMPLEMENTED response
     * for the workflow/* stubs. The wire shape matches the
     * app-spec: {ok: false, error: {name, code, message}}.
     * Java-A's Phase 2 work will replace this with a real
     * implementation.
     */
    private static Map<String, Object> notImplemented(String method) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("name", "NOT_IMPLEMENTED");
        err.put("code", -32054);
        err.put("message", method + " is scheduled for Phase 2 (workflows module)");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", err);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> coerceParams(Object params) {
        if (params == null) return Map.of();
        if (params instanceof Map<?,?> m) return (Map<String, Object>) m;
        try {
            JsonNode node = mapper.valueToTree(params);
            return mapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("params must be an object, got "
                    + params.getClass().getSimpleName());
        }
    }

    private String encode(JsonRpcEnvelope.Response response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,"
                    + "\"message\":\"encode-failed: " + SupervisorSocket.jsonEscape(e.getMessage())
                    + "\"}}";
        }
    }

    /** Friendly Map builder for service results. */
    public static Map<String, Object> result(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("kv must be pairs");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
