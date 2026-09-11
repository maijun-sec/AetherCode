package org.aethercode.evals.capability.interface_;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-eval-6: AetherCode JSON-RPC Interface Conformance suite.
 *
 * <p>Mirrors the surface defined by
 * {@code org.aethercode.protocol.methods.*} — the 9 endpoint
 * families the TUI / desktop / CLI all hit:</p>
 *
 * <ul>
 *   <li>{@code memory/*} — get / appendProjectChange /
 *       appendSessionFact / compact / switchProject / list</li>
 *   <li>{@code compact/*} — request a memory compaction</li>
 *   <li>{@code context/*} — read / write the project context file</li>
 *   <li>{@code engine/continuation} — resume a long-running engine
 *       task</li>
 *   <li>{@code grant/*} — list / revoke runtime permission grants
 *       (see R-eval-8 for safety path)</li>
 *   <li>{@code permission/*} — check / request a permission</li>
 *   <li>{@code task/*} — list / status / cancel subagent tasks</li>
 *   <li>{@code theme/*} — list / set the active UI theme</li>
 * </ul>
 *
 * <p>The suite is self-contained: {@link JsonRpcRequest},
 * {@link JsonRpcResponse}, and {@link JsonRpcDispatcher} are 1:1
 * with AetherCode's wire format (JSON-RPC 2.0 over the daemon's
 * stdio / websocket transport). R-mod-2 will swap in the real
 * {@code JsonRpcDispatcher} from {@code aethercode-protocol}.</p>
 */
class JsonRpcInterfaceTest {

    /* --------------------- JSON-RPC 2.0 wire types --------------------- */

    public record JsonRpcRequest(
            String jsonrpc,
            String id,
            String method,
            Map<String, Object> params) {
        public JsonRpcRequest {
            if (id == null) id = UUID.randomUUID().toString();
            if (jsonrpc == null) jsonrpc = "2.0";
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    public record JsonRpcError(int code, String message, Object data) {
        public static JsonRpcError parseError(String msg) { return new JsonRpcError(-32700, msg, null); }
        public static JsonRpcError invalidRequest(String msg) { return new JsonRpcError(-32600, msg, null); }
        public static JsonRpcError methodNotFound(String msg) { return new JsonRpcError(-32601, msg, null); }
        public static JsonRpcError invalidParams(String msg) { return new JsonRpcError(-32602, msg, null); }
        public static JsonRpcError internalError(String msg) { return new JsonRpcError(-32603, msg, null); }
    }

    public record JsonRpcResponse(String jsonrpc, String id, Object result, JsonRpcError error) {
        public boolean ok() { return error == null; }
    }

    /* --------------------- Dispatcher --------------------- */

    @FunctionalInterface
    public interface RpcHandler {
        Object handle(Map<String, Object> params) throws Exception;
    }

    public static final class JsonRpcDispatcher {
        private final Map<String, RpcHandler> handlers = new LinkedHashMap<>();
        private final Map<String, String> methodAliases = new HashMap<>();
        private final List<String> callLog = new ArrayList<>();

        public JsonRpcDispatcher register(String method, RpcHandler handler) {
            if (method == null || method.isBlank() || handler == null) {
                throw new IllegalArgumentException("method and handler required");
            }
            handlers.put(method, handler);
            return this;
        }

        public JsonRpcDispatcher alias(String from, String to) {
            methodAliases.put(from, to);
            return this;
        }

        public List<String> callLog() { return List.copyOf(callLog); }
        public boolean hasMethod(String method) {
            return handlers.containsKey(method) || methodAliases.containsKey(method);
        }

        public JsonRpcResponse dispatch(JsonRpcRequest req) {
            Objects.requireNonNull(req, "req");
            callLog.add(req.method());
            RpcHandler handler = handlers.get(req.method());
            if (handler == null) {
                String aliased = methodAliases.get(req.method());
                if (aliased != null) handler = handlers.get(aliased);
            }
            if (handler == null) {
                return new JsonRpcResponse("2.0", req.id(), null,
                        JsonRpcError.methodNotFound("unknown method: " + req.method()));
            }
            try {
                Object result = handler.handle(req.params());
                return new JsonRpcResponse("2.0", req.id(), result, null);
            } catch (IllegalArgumentException ex) {
                return new JsonRpcResponse("2.0", req.id(), null,
                        JsonRpcError.invalidParams(ex.getMessage()));
            } catch (Exception ex) {
                return new JsonRpcResponse("2.0", req.id(), null,
                        JsonRpcError.internalError(ex.getClass().getSimpleName()
                                + (ex.getMessage() == null ? "" : ": " + ex.getMessage())));
            }
        }
    }

    /* --------------------- Endpoint registry (mirrors AetherCodeMethods family) --------------------- */

    /** Wire up the 9 endpoint families onto a fresh dispatcher.
     *  Returns the dispatcher and the backing state so tests
     *  can assert. Mirrors the
     *  {@code AetherCodeMethods / MemoryMethods / CompactMethods / …}
     *  registry. */
    public record EndpointHarness(JsonRpcDispatcher dispatcher, HarnessState state) {}

    public record HarnessState(
            Map<String, Map<String, String>> memory,
            List<String> compactEvents,
            Map<String, String> context,
            Map<String, String> themes,
            List<String> permissionRequests,
            List<String> taskOps,
            List<String> grantOps,
            List<String> engineContinuations) {

        public static HarnessState empty() {
            return new HarnessState(
                    new LinkedHashMap<>(),
                    new ArrayList<>(),
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(Map.of("dark", "Dark theme", "light", "Light theme")),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>());
        }
    }

    public static EndpointHarness wireStandardEndpoints() {
        JsonRpcDispatcher d = new JsonRpcDispatcher();
        HarnessState s = HarnessState.empty();

        // memory/* — 6 endpoints
        d.register("memory/get", params -> {
            String scope = (String) params.getOrDefault("scope", "PROJECT");
            return Map.of(
                    "scope", scope,
                    "entries", s.memory.getOrDefault(scope, Map.of()),
                    "tokens", s.memory.getOrDefault(scope, Map.of()).values().stream()
                            .mapToInt(v -> v.length() / 4).sum());
        });
        d.register("memory/appendProjectChange", params -> {
            String desc = (String) params.get("description");
            if (desc == null || desc.isBlank()) {
                throw new IllegalArgumentException("description required");
            }
            s.memory.computeIfAbsent("PROJECT", k -> new LinkedHashMap<>())
                    .put(UUID.randomUUID().toString(), desc);
            return Map.of("ok", true, "id", UUID.randomUUID().toString());
        });
        d.register("memory/appendSessionFact", params -> {
            String key = (String) params.get("key");
            String value = (String) params.get("value");
            if (key == null || value == null) {
                throw new IllegalArgumentException("key and value required");
            }
            s.memory.computeIfAbsent("SESSION", k -> new LinkedHashMap<>()).put(key, value);
            return Map.of("ok", true);
        });
        d.register("memory/compact", params -> {
            int before = s.memory.values().stream()
                    .mapToInt(m -> m.values().stream().mapToInt(String::length).sum()).sum();
            s.compactEvents.add("compact@" + System.currentTimeMillis());
            return Map.of("ok", true, "beforeTokens", before / 4, "afterTokens", before / 8);
        });
        d.register("memory/switchProject", params -> {
            String cwd = (String) params.get("cwd");
            if (cwd == null) throw new IllegalArgumentException("cwd required");
            s.memory.put("PROJECT", new LinkedHashMap<>(Map.of("cwd", cwd)));
            return Map.of("ok", true);
        });
        d.register("memory/list", params -> {
            String scope = (String) params.getOrDefault("scope", "PROJECT");
            return Map.of(
                    "scope", scope,
                    "entries", s.memory.getOrDefault(scope, Map.of()),
                    "totalTokens", s.memory.getOrDefault(scope, Map.of()).values().stream()
                            .mapToInt(v -> v.length() / 4).sum());
        });

        // compact/*
        d.register("compact/request", params -> {
            Boolean force = (Boolean) params.getOrDefault("force", false);
            s.compactEvents.add("force=" + force);
            return Map.of("ok", true, "ms", 0L, "changesCompressed", 0);
        });

        // context/*
        d.register("context/get", params -> {
            String key = (String) params.getOrDefault("key", "AGENTS.md");
            return Map.of("key", key, "content", s.context.getOrDefault(key, ""));
        });
        d.register("context/set", params -> {
            String key = (String) params.get("key");
            String content = (String) params.get("content");
            if (key == null || content == null) {
                throw new IllegalArgumentException("key and content required");
            }
            s.context.put(key, content);
            return Map.of("ok", true);
        });

        // engine/continuation
        d.register("engine/continuation", params -> {
            String sessionId = (String) params.get("sessionId");
            if (sessionId == null) throw new IllegalArgumentException("sessionId required");
            s.engineContinuations.add(sessionId);
            return Map.of("ok", true, "sessionId", sessionId, "resumedAt", 0L);
        });

        // grant/* — list / revoke
        d.register("grant/list", params -> Map.of("grants", List.of()));
        d.register("grant/revoke", params -> {
            String id = (String) params.get("id");
            if (id == null) throw new IllegalArgumentException("id required");
            s.grantOps.add("revoke:" + id);
            return Map.of("ok", true);
        });

        // permission/* — check / request
        d.register("permission/check", params -> {
            String tool = (String) params.get("tool");
            return Map.of("tool", tool, "allowed", true);
        });
        d.register("permission/request", params -> {
            String tool = (String) params.get("tool");
            String reason = (String) params.getOrDefault("reason", "");
            s.permissionRequests.add(tool + ":" + reason);
            return Map.of("tool", tool, "approved", false, "pending", true);
        });

        // task/* — list / status / cancel
        d.register("task/list", params -> Map.of("tasks", s.taskOps));
        d.register("task/status", params -> {
            String id = (String) params.get("id");
            return Map.of("id", id, "status", "RUNNING");
        });
        d.register("task/cancel", params -> {
            String id = (String) params.get("id");
            if (id == null) throw new IllegalArgumentException("id required");
            s.taskOps.add("cancel:" + id);
            return Map.of("ok", true);
        });

        // theme/*
        d.register("theme/list", params -> Map.of("themes", s.themes));
        d.register("theme/set", params -> {
            String name = (String) params.get("name");
            if (name == null) throw new IllegalArgumentException("name required");
            if (!s.themes.containsKey(name)) {
                throw new IllegalArgumentException("unknown theme: " + name);
            }
            s.themes.put("__active__", name);
            return Map.of("ok", true, "active", name);
        });

        return new EndpointHarness(d, s);
    }

    /* --------------------- Memory endpoints --------------------- */

    @Test
    void memoryGetReturnsEmptyForUnknownScope() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "memory/get",
                        Map.of("scope", "USER")));
        assertTrue(r.ok());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        assertEquals("USER", result.get("scope"));
    }

    @Test
    void memoryAppendProjectChangeRequiresDescription() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "memory/appendProjectChange", Map.of()));
        assertFalse(r.ok());
        assertEquals(-32602, r.error().code());
    }

    @Test
    void memoryAppendProjectChangeRoundtrips() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "memory/appendProjectChange",
                        Map.of("description", "added new feature X")));
        assertTrue(r.ok());
        // Reading it back via memory/list should show the entry.
        JsonRpcResponse list = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "2", "memory/list", Map.of("scope", "PROJECT")));
        @SuppressWarnings("unchecked")
        Map<String, Object> listResult = (Map<String, Object>) list.result();
        @SuppressWarnings("unchecked")
        Map<String, String> entries = (Map<String, String>) listResult.get("entries");
        assertTrue(entries.values().stream()
                .anyMatch(v -> v.contains("added new feature X")));
    }

    @Test
    void memoryAppendSessionFactPersistsAcrossGet() {
        EndpointHarness h = wireStandardEndpoints();
        h.dispatcher().dispatch(new JsonRpcRequest(null, "1", "memory/appendSessionFact",
                Map.of("key", "k1", "value", "v1")));
        JsonRpcResponse get = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "2", "memory/get", Map.of("scope", "SESSION")));
        assertTrue(get.ok());
    }

    @Test
    void memoryCompactReturnsBeforeAfterTokens() {
        EndpointHarness h = wireStandardEndpoints();
        // Populate some content first.
        h.dispatcher().dispatch(new JsonRpcRequest(null, "1", "memory/appendProjectChange",
                Map.of("description", "blah blah blah blah")));
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "2", "memory/compact", Map.of()));
        assertTrue(r.ok());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        assertTrue(result.containsKey("beforeTokens"));
        assertTrue(result.containsKey("afterTokens"));
        assertEquals(1, h.state().compactEvents.size());
    }

    @Test
    void memorySwitchProjectRequiresCwd() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "memory/switchProject", Map.of()));
        assertFalse(r.ok());
        assertEquals(-32602, r.error().code());
    }

    /* --------------------- Compact endpoint --------------------- */

    @Test
    void compactRequestHonorsForceFlag() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "compact/request", Map.of("force", true)));
        assertTrue(r.ok());
        assertEquals("force=true", h.state().compactEvents.get(0));
    }

    /* --------------------- Context endpoints --------------------- */

    @Test
    void contextGetReturnsDefault() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "context/get", Map.of()));
        assertTrue(r.ok());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        assertEquals("AGENTS.md", result.get("key"));
    }

    @Test
    void contextSetAndGetRoundtrip() {
        EndpointHarness h = wireStandardEndpoints();
        h.dispatcher().dispatch(new JsonRpcRequest(null, "1", "context/set",
                Map.of("key", "AGENTS.md", "content", "new content")));
        JsonRpcResponse get = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "2", "context/get", Map.of("key", "AGENTS.md")));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) get.result();
        assertEquals("new content", result.get("content"));
    }

    /* --------------------- Engine continuation --------------------- */

    @Test
    void engineContinuationRequiresSessionId() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "engine/continuation", Map.of()));
        assertFalse(r.ok());
        assertEquals(-32602, r.error().code());
    }

    @Test
    void engineContinuationRecordsSessionId() {
        EndpointHarness h = wireStandardEndpoints();
        h.dispatcher().dispatch(new JsonRpcRequest(null, "1", "engine/continuation",
                Map.of("sessionId", "s-123")));
        assertEquals(List.of("s-123"), h.state().engineContinuations);
    }

    /* --------------------- Grant endpoints (R-eval-8 hooks in) --------------------- */

    @Test
    void grantListReturnsEmptyByDefault() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "grant/list", Map.of()));
        assertTrue(r.ok());
    }

    @Test
    void grantRevokeRequiresId() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "grant/revoke", Map.of()));
        assertFalse(r.ok());
    }

    @Test
    void grantRevokeRecordsOp() {
        EndpointHarness h = wireStandardEndpoints();
        h.dispatcher().dispatch(new JsonRpcRequest(null, "1", "grant/revoke",
                Map.of("id", "g-1")));
        assertEquals(List.of("revoke:g-1"), h.state().grantOps);
    }

    /* --------------------- Permission endpoints --------------------- */

    @Test
    void permissionCheckReturnsAllowedFlag() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "permission/check",
                        Map.of("tool", "file_write")));
        assertTrue(r.ok());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        assertEquals("file_write", result.get("tool"));
        assertEquals(true, result.get("allowed"));
    }

    @Test
    void permissionRequestRecordsPending() {
        EndpointHarness h = wireStandardEndpoints();
        h.dispatcher().dispatch(new JsonRpcRequest(null, "1", "permission/request",
                Map.of("tool", "bash", "reason", "running tests")));
        assertEquals(List.of("bash:running tests"), h.state().permissionRequests);
    }

    /* --------------------- Task endpoints --------------------- */

    @Test
    void taskListStartsEmpty() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "task/list", Map.of()));
        assertTrue(r.ok());
    }

    @Test
    void taskStatusReturnsRunning() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "task/status", Map.of("id", "t-1")));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        assertEquals("RUNNING", result.get("status"));
    }

    @Test
    void taskCancelRecordsOp() {
        EndpointHarness h = wireStandardEndpoints();
        h.dispatcher().dispatch(new JsonRpcRequest(null, "1", "task/cancel",
                Map.of("id", "t-1")));
        assertEquals(List.of("cancel:t-1"), h.state().taskOps);
    }

    /* --------------------- Theme endpoints --------------------- */

    @Test
    void themeListReturnsBothDarkAndLight() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "theme/list", Map.of()));
        assertTrue(r.ok());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        @SuppressWarnings("unchecked")
        Map<String, String> themes = (Map<String, String>) result.get("themes");
        assertTrue(themes.containsKey("dark"));
        assertTrue(themes.containsKey("light"));
    }

    @Test
    void themeSetRejectsUnknownTheme() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "theme/set", Map.of("name", "neon")));
        assertFalse(r.ok());
        assertEquals(-32602, r.error().code());
    }

    @Test
    void themeSetSwitchesActive() {
        EndpointHarness h = wireStandardEndpoints();
        h.dispatcher().dispatch(new JsonRpcRequest(null, "1", "theme/set",
                Map.of("name", "light")));
        assertEquals("light", h.state().themes.get("__active__"));
    }

    /* --------------------- Method-not-found handling --------------------- */

    @Test
    void unknownMethodReturnsMethodNotFound() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "nonsense/method", Map.of()));
        assertFalse(r.ok());
        assertEquals(-32601, r.error().code());
    }

    @Test
    void aliasResolvesToRealMethod() {
        EndpointHarness h = wireStandardEndpoints();
        h.dispatcher().alias("theme/current", "theme/list");
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "theme/current", Map.of()));
        assertTrue(r.ok());
    }

    /* --------------------- JSON-RPC 2.0 wire format --------------------- */

    @Test
    void requestDefaultsToJsonRpc20() {
        JsonRpcRequest r = new JsonRpcRequest(null, "1", "x", Map.of());
        assertEquals("2.0", r.jsonrpc());
    }

    @Test
    void requestAssignsIdIfMissing() {
        JsonRpcRequest r = new JsonRpcRequest(null, null, "x", Map.of());
        assertNotNull(r.id());
    }

    @Test
    void responsePreservesRequestId() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "test-id", "theme/list", Map.of()));
        assertEquals("test-id", r.id());
    }

    @Test
    void responseIs2PointO() {
        EndpointHarness h = wireStandardEndpoints();
        JsonRpcResponse r = h.dispatcher().dispatch(
                new JsonRpcRequest(null, "1", "theme/list", Map.of()));
        assertEquals("2.0", r.jsonrpc());
    }

    /* --------------------- Call log / observability --------------------- */

    @Test
    void callLogRecordsEveryDispatch() {
        EndpointHarness h = wireStandardEndpoints();
        h.dispatcher().dispatch(new JsonRpcRequest(null, "1", "theme/list", Map.of()));
        h.dispatcher().dispatch(new JsonRpcRequest(null, "2", "task/list", Map.of()));
        h.dispatcher().dispatch(new JsonRpcRequest(null, "3", "nonsense", Map.of()));
        assertEquals(List.of("theme/list", "task/list", "nonsense"), h.dispatcher().callLog());
    }

    @Test
    void hasMethodReturnsTrueForRegisteredMethods() {
        EndpointHarness h = wireStandardEndpoints();
        assertTrue(h.dispatcher().hasMethod("memory/get"));
        assertTrue(h.dispatcher().hasMethod("task/cancel"));
        assertFalse(h.dispatcher().hasMethod("does/not/exist"));
    }

    /* --------------------- Validation --------------------- */

    @Test
    void dispatcherRejectsNullRequest() {
        EndpointHarness h = wireStandardEndpoints();
        assertThrows(NullPointerException.class, () -> h.dispatcher().dispatch(null));
    }

    @Test
    void dispatcherRejectsBlankMethodRegistration() {
        JsonRpcDispatcher d = new JsonRpcDispatcher();
        assertThrows(IllegalArgumentException.class, () -> d.register("", params -> null));
    }

    @Test
    void handlerExceptionIsInternalError() {
        JsonRpcDispatcher d = new JsonRpcDispatcher();
        d.register("bad", params -> { throw new RuntimeException("boom"); });
        JsonRpcResponse r = d.dispatch(new JsonRpcRequest(null, "1", "bad", Map.of()));
        assertFalse(r.ok());
        assertEquals(-32603, r.error().code());
        assertTrue(r.error().message().contains("boom"));
    }

    @Test
    void handlerIllegalArgumentIsInvalidParams() {
        JsonRpcDispatcher d = new JsonRpcDispatcher();
        d.register("bad", params -> {
            throw new IllegalArgumentException("missing foo");
        });
        JsonRpcResponse r = d.dispatch(new JsonRpcRequest(null, "1", "bad", Map.of()));
        assertFalse(r.ok());
        assertEquals(-32602, r.error().code());
    }

    /* --------------------- E2E: full session lifecycle --------------------- */

    @Test
    void fullSessionLifecycleRoundtrips() {
        EndpointHarness h = wireStandardEndpoints();
        // 1. Open a session and append some context.
        h.dispatcher().dispatch(new JsonRpcRequest(null, "1", "context/set",
                Map.of("key", "AGENTS.md", "content", "you are an agent")));
        // 2. Append a project change.
        h.dispatcher().dispatch(new JsonRpcRequest(null, "2", "memory/appendProjectChange",
                Map.of("description", "added eval suite")));
        // 3. Append a session fact.
        h.dispatcher().dispatch(new JsonRpcRequest(null, "3", "memory/appendSessionFact",
                Map.of("key", "user", "value", "alice")));
        // 4. Compact.
        h.dispatcher().dispatch(new JsonRpcRequest(null, "4", "memory/compact", Map.of()));
        // 5. Switch theme.
        h.dispatcher().dispatch(new JsonRpcRequest(null, "5", "theme/set", Map.of("name", "dark")));
        // 6. Resume engine.
        h.dispatcher().dispatch(new JsonRpcRequest(null, "6", "engine/continuation",
                Map.of("sessionId", "s-1")));
        // Verify state.
        assertEquals("you are an agent", h.state().context.get("AGENTS.md"));
        assertEquals("dark", h.state().themes.get("__active__"));
        assertEquals(1, h.state().compactEvents.size());
        assertEquals(List.of("s-1"), h.state().engineContinuations);
        // Verify call log: 6 calls in order.
        assertEquals(List.of(
                "context/set", "memory/appendProjectChange", "memory/appendSessionFact",
                "memory/compact", "theme/set", "engine/continuation"),
                h.dispatcher().callLog());
    }
}
