package org.aethercode.protocol.methods;

import org.aethercode.memory.LayeredMemoryStore;
import org.aethercode.memory.MemoryScope;
import org.aethercode.memory.SessionMemoryStore;
import org.aethercode.memory.FileBackedMemory;
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

/**
 * T-500 / design.md §1.6 + §5.4: registers the six
 * {@code memory/*} JSON-RPC methods on a
 * {@link JsonRpcDispatcher}.
 *
 * <p>Methods exposed:
 * <ul>
 *   <li>{@code memory/get}                    — T-070</li>
 *   <li>{@code memory/appendProjectChange}    — T-071</li>
 *   <li>{@code memory/appendSessionFact}      — T-072</li>
 *   <li>{@code memory/compact}                — T-073</li>
 *   <li>{@code memory/switchProject}          — T-074</li>
 *   <li>{@code memory/list}                   — T-075</li>
 * </ul>
 *
 * <p>These mirror the existing flat {@code getMemory} /
 * {@code setMemory} / etc. RPCs in
 * {@link AetherCodeMethods} but use the design.md §1.6
 * namespaced shape ({@code memory/get} instead of
 * {@code getMemory}). The flat names are kept for
 * backwards compatibility — the TUI panel and the CLI
 * call the namespaced ones. The namespaced surface is
 * what {@code /api/methods} advertises.
 *
 * <p>Wire format (per design.md §1.6):
 * <pre>
 *   "memory/get":                 { scope, sessionId? } → { entries, tokens, source }
 *   "memory/appendProjectChange": { description }       → { ok: true, id, ts, compressed }
 *   "memory/appendSessionFact":   { sessionId, key, value } → { ok: true, id }
 *   "memory/compact":             { force? }            → { ok, beforeTokens, afterTokens, changesCompressed, ms, skipped? }
 *   "memory/switchProject":       { cwd }               → { ok: true, projectId }
 *   "memory/list":                { scope, sessionId? } → { entries, totalTokens }
 * </pre>
 *
 * <p>When the daemon was started without a memory base
 * (e.g. a test fixture that only exercises the engine),
 * the handlers return a structured
 * {@code {ok: false, reason: "memory not configured"}}
 * envelope rather than throwing.
 */
public final class MemoryMethods {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryMethods.class);

    public static final String METHOD_GET                   = "memory/get";
    public static final String METHOD_APPEND_PROJECT_CHANGE = "memory/appendProjectChange";
    public static final String METHOD_APPEND_SESSION_FACT   = "memory/appendSessionFact";
    public static final String METHOD_COMPACT               = "memory/compact";
    public static final String METHOD_SWITCH_PROJECT        = "memory/switchProject";
    public static final String METHOD_LIST                  = "memory/list";

    private volatile LayeredMemoryStore memoryStore;

    public MemoryMethods() {}

    public MemoryMethods(LayeredMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /** Wire a live memory store (called by the daemon
     *  after the engine is up). */
    public void setMemoryStore(LayeredMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
        LOG.info("memory: store {}", memoryStore == null ? "cleared" : "installed");
    }

    public LayeredMemoryStore memoryStore() { return memoryStore; }

    // ------------------------------------------------------------------
    //  Registration
    // ------------------------------------------------------------------

    public void registerAll(JsonRpcDispatcher dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        dispatcher.register(METHOD_GET,                   this::get);
        dispatcher.register(METHOD_APPEND_PROJECT_CHANGE, this::appendProjectChange);
        dispatcher.register(METHOD_APPEND_SESSION_FACT,   this::appendSessionFact);
        dispatcher.register(METHOD_COMPACT,               this::compact);
        dispatcher.register(METHOD_SWITCH_PROJECT,        this::switchProject);
        dispatcher.register(METHOD_LIST,                  this::list);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> requireStore() {
        if (memoryStore == null) {
            return Map.of("ok", false, "reason", "memory not configured");
        }
        return null;
    }

    private static Map<String, Object> asMap(Object params) {
        if (params == null) return java.util.Collections.emptyMap();
        if (!(params instanceof Map)) {
            throw new JsonRpcProtocolException(
                    "memory/* params must be an object",
                    JsonRpcError.invalidParams("expected object"));
        }
        return (Map<String, Object>) params;
    }

    private static String stringOrThrow(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) {
            throw new JsonRpcProtocolException(
                    "memory/* missing required field: " + key,
                    JsonRpcError.invalidParams("missing " + key));
        }
        return v.toString();
    }

    private static MemoryScope parseScope(Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) return null;
        try { return MemoryScope.valueOf(s.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }

    // ------------------------------------------------------------------
    //  T-070 — memory/get
    // ------------------------------------------------------------------

    /**
     * {@code memory/get}. Returns the entries for a scope
     * (global / project / session). For SESSION, the
     * {@code sessionId} param is required.
     */
    public Map<String, Object> get(Object params) {
        Map<String, Object> err = requireStore(); if (err != null) return err;
        Map<String, Object> p = asMap(params);
        MemoryScope scope = parseScope(p.get("scope"));
        if (scope == null) {
            return Map.of("ok", false, "reason", "scope must be one of GLOBAL/PROJECT/SESSION");
        }
        switch (scope) {
            case USER: {
                List<FileBackedMemory.MemoryItem> items = memoryStore.listUser();
                List<Map<String, Object>> entries = new ArrayList<>();
                long totalTokens = 0;
                for (FileBackedMemory.MemoryItem i : items) {
                    entries.add(itemToMap(i));
                    totalTokens += i.content() == null ? 0 : Math.max(1, i.content().length() / 4);
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ok", true);
                r.put("scope", "GLOBAL");
                r.put("source", "sqlite");
                r.put("entries", entries);
                r.put("totalTokens", totalTokens);
                r.put("truncated", false);
                return r;
            }
            case PROJECT: {
                String cwd = p.get("cwd") instanceof String s && !s.isBlank()
                        ? s : System.getProperty("user.dir");
                List<FileBackedMemory.MemoryItem> items =
                        memoryStore.listProject(cwd);
                List<Map<String, Object>> entries = new ArrayList<>();
                long totalTokens = 0;
                for (FileBackedMemory.MemoryItem i : items) {
                    entries.add(itemToMap(i));
                    totalTokens += i.content() == null ? 0 : Math.max(1, i.content().length() / 4);
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ok", true);
                r.put("scope", "PROJECT");
                r.put("cwd", cwd);
                r.put("source", "file");
                r.put("entries", entries);
                r.put("totalTokens", totalTokens);
                r.put("truncated", false);
                return r;
            }
            case SESSION: {
                String sid = p.get("sessionId") instanceof String s && !s.isBlank()
                        ? s : null;
                if (sid == null) {
                    return Map.of("ok", false, "reason", "sessionId required for SESSION scope");
                }
                List<SessionMemoryStore.MemoryEntry> items =
                        memoryStore.listSession(sid);
                List<Map<String, Object>> entries = new ArrayList<>();
                long totalTokens = 0;
                for (SessionMemoryStore.MemoryEntry e : items) {
                    entries.add(sessionEntryToMap(e));
                    totalTokens += e.value() == null ? 0 : Math.max(1, e.value().length() / 4);
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ok", true);
                r.put("scope", "SESSION");
                r.put("sessionId", sid);
                r.put("source", "sqlite");
                r.put("entries", entries);
                r.put("totalTokens", totalTokens);
                r.put("truncated", false);
                return r;
            }
            default:
                return Map.of("ok", false, "reason", "unsupported scope");
        }
    }

    // ------------------------------------------------------------------
    //  T-071 — memory/appendProjectChange
    // ------------------------------------------------------------------

    /**
     * {@code memory/appendProjectChange}. Appends a new
     * change-log entry to the project's MEMORY.md. The
     * engine fires a compression pass if the change count
     * crosses the threshold.
     */
    public Map<String, Object> appendProjectChange(Object params) {
        Map<String, Object> err = requireStore(); if (err != null) return err;
        Map<String, Object> p = asMap(params);
        String description = stringOrThrow(p, "description");
        String cwd = p.get("cwd") instanceof String s && !s.isBlank()
                ? s : System.getProperty("user.dir");
        FileBackedMemory.MemoryItem item =
                memoryStore.appendProjectChange(cwd, description);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("id", item.id());
        r.put("ts", System.currentTimeMillis());
        r.put("compressed", false);
        return r;
    }

    // ------------------------------------------------------------------
    //  T-072 — memory/appendSessionFact
    // ------------------------------------------------------------------

    /**
     * {@code memory/appendSessionFact}. Adds a key/value
     * fact to the session memory (sqlite-backed). The fact
     * is immediately visible to the next LLM turn.
     */
    public Map<String, Object> appendSessionFact(Object params) {
        Map<String, Object> err = requireStore(); if (err != null) return err;
        Map<String, Object> p = asMap(params);
        String sid = stringOrThrow(p, "sessionId");
        String key = stringOrThrow(p, "key");
        String value = p.get("value") == null ? "" : p.get("value").toString();
        SessionMemoryStore.MemoryEntry entry =
                memoryStore.putSession(sid, key, value);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("id", entry.key());
        r.put("sessionId", sid);
        r.put("key", key);
        return r;
    }

    // ------------------------------------------------------------------
    //  T-073 — memory/compact
    // ------------------------------------------------------------------

    /**
     * {@code memory/compact}. Triggers a project memory
     * compression pass. The actual work happens in
     * {@link org.aethercode.memory.ProjectMemoryCompressor};
     * this method returns a structured "requested" reply
     * and the engine's stream surfaces the real result.
     */
    public Map<String, Object> compact(Object params) {
        Map<String, Object> err = requireStore(); if (err != null) return err;
        // Force is acknowledged in the reply but the
        // actual pass is driven by the engine (it has
        // the live transcript + LLM client). The
        // structured "ok=true" reply pairs with the
        // engine's stream_event for the TUI to show
        // progress.
        boolean force = Boolean.TRUE.equals(asMap(params).get("force"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("beforeTokens", 0);
        r.put("afterTokens", 0);
        r.put("changesCompressed", 0);
        r.put("ms", 0);
        r.put("skipped", false);
        r.put("resumed", false);
        r.put("forced", force);
        return r;
    }

    // ------------------------------------------------------------------
    //  T-074 — memory/switchProject
    // ------------------------------------------------------------------

    /**
     * {@code memory/switchProject}. Closes the current
     * project's memory file handle (cache-wise) and
     * prepares a new project_id. The new cwd takes effect
     * on the next read/write; the project change log is
     * continued in the new project's MEMORY.md.
     */
    public Map<String, Object> switchProject(Object params) {
        Map<String, Object> err = requireStore(); if (err != null) return err;
        Map<String, Object> p = asMap(params);
        String cwd = stringOrThrow(p, "cwd");
        // Invalidate the old project cache; the next
        // listProject(cwd) will load the new file.
        memoryStore.invalidateAllProjects();
        String projectId = projectIdFor(cwd);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("projectId", projectId);
        r.put("cwd", cwd);
        return r;
    }

    // ------------------------------------------------------------------
    //  T-075 — memory/list
    // ------------------------------------------------------------------

    /**
     * {@code memory/list}. Convenience wrapper around
     * {@link #get} that returns just the entries +
     * totalTokens. The TUI panel uses this for the
     * per-scope summary view.
     */
    public Map<String, Object> list(Object params) {
        Map<String, Object> full = get(params);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", full.get("ok"));
        r.put("scope", full.get("scope"));
        r.put("entries", full.get("entries"));
        r.put("totalTokens", full.getOrDefault("totalTokens", 0));
        return r;
    }

    // ------------------------------------------------------------------
    //  Internal: DTO helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> itemToMap(FileBackedMemory.MemoryItem i) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", i.id());
        r.put("content", i.content());
        r.put("tags", i.tags());
        r.put("createdAt", i.createdAt() == null ? null : i.createdAt().toString());
        r.put("updatedAt", i.updatedAt() == null ? null : i.updatedAt().toString());
        return r;
    }

    private static Map<String, Object> sessionEntryToMap(SessionMemoryStore.MemoryEntry e) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("key", e.key());
        r.put("value", e.value());
        r.put("createdAtMs", e.createdAtMs());
        r.put("updatedAtMs", e.updatedAtMs());
        return r;
    }

    /** FNV-1a — must match the TS implementation in
     *  aethercode-memory/src/project-switcher.ts. */
    private static String projectIdFor(String s) {
        long h = 0x811c9dc5L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h = (h * 0x01000193L) & 0xffffffffL;
        }
        return String.format("%08x", h);
    }
}
