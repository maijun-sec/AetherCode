package org.aethercode.protocol.methods;

import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pre-tool approval flow + new status RPC.
 *
 * <p>Pins the user-facing contract for the "ask before every
 * non-read-only tool call" mode:
 * <ol>
 *   <li>{@code askPermission(runId, tool, input, reason, riskLevel)}
 *       emits a {@code permission_request} notification with
 *       {@code pendingCount} in the payload (prior round).</li>
 *   <li>The legacy hard-coded {@code "n/a"} runId is replaced
 *       with the caller's runId (or kept as {@code "n/a"} when
 *       null / blank).</li>
 *   <li>{@code pendingPermissionCount()} and
 *       {@code pendingPermissionSnapshot()} let a TUI render a
 *       "N permission(s) pending" badge on (re)connect.</li>
 *   <li>{@code permissionResponse(requestId, decision, reason)}
 *       still completes the future and removes it from the queue.</li>
 *   <li>{@code getPermissionStatus(params)} returns the same
 *       snapshot as a read-only RPC for late-joining clients.</li>
 * </ol>
 */
class AetherCodeMethodsR163Test {

    private static AetherCodeEngine engineFor(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    private static AetherCodeMethods methodsWith(Path cwd, List<JsonRpcNotification> out) {
        return new AetherCodeMethods(engineFor(cwd), n -> out.add(n));
    }

    @Test
    void askPermission_emitsRequestWithPendingCount(@TempDir Path cwd) {
        List<JsonRpcNotification> out = new ArrayList<>();
        AetherCodeMethods m = methodsWith(cwd, out);
        CompletableFuture<AetherCodeMethods.PermissionDecision> fut = m.askPermission(
                "run-42", "file_write",
                Map.of("file_path", "/tmp/x"),
                "demo", "medium");
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) out.get(0).params();
        // pendingCount is in every notification so a TUI
        // that joins late can render a status badge without
        // re-querying.
        assertThat(p.get("pendingCount")).isEqualTo(1);
        assertThat(p.get("runId")).isEqualTo("run-42");
        assertThat(p.get("tool")).isEqualTo("file_write");
        // Complete so the future doesn't leak across tests.
        m.permissionResponse(Map.of(
                "requestId", p.get("requestId"),
                "decision", AetherCodeMethods.DECISION_ALLOW,
                "reason", "test"));
        fut.orTimeout(1, TimeUnit.SECONDS).join();
    }

    @Test
    void askPermission_runIdDefaultsToNa_whenBlankOrNull(@TempDir Path cwd) {
        List<JsonRpcNotification> out = new ArrayList<>();
        AetherCodeMethods m = methodsWith(cwd, out);
        AtomicReference<String> r1 = new AtomicReference<>();
        AtomicReference<String> r2 = new AtomicReference<>();
        m.askPermission(null, "bash", Map.of("command", "ls"), "x", "low")
                .whenComplete((d, e) -> {});
        r1.set((String) ((Map<?, ?>) out.get(0).params()).get("runId"));
        m.askPermission("", "bash", Map.of("command", "ls"), "x", "low")
                .whenComplete((d, e) -> {});
        r2.set((String) ((Map<?, ?>) out.get(1).params()).get("runId"));
        m.askPermission("   ", "bash", Map.of("command", "ls"), "x", "low")
                .whenComplete((d, e) -> {});
        String r3 = (String) ((Map<?, ?>) out.get(2).params()).get("runId");
        assertThat(r1.get()).isEqualTo("n/a");
        assertThat(r2.get()).isEqualTo("n/a");
        assertThat(r3).isEqualTo("n/a");
        // Drain the futures so they don't leak.
        for (JsonRpcNotification n : out) {
            String id = (String) ((Map<?, ?>) n.params()).get("requestId");
            m.permissionResponse(Map.of("requestId", id, "decision", AetherCodeMethods.DECISION_DENY, "reason", "drain"));
        }
    }

    @Test
    void askPermission_runIdPassedThrough(@TempDir Path cwd) {
        List<JsonRpcNotification> out = new ArrayList<>();
        AetherCodeMethods m = methodsWith(cwd, out);
        m.askPermission("run-7", "file_edit", Map.of("file_path", "/tmp/y"),
                "edit", "medium").whenComplete((d, e) -> {});
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) out.get(0).params();
        assertThat(p.get("runId")).isEqualTo("run-7");
        // Drain.
        m.permissionResponse(Map.of(
                "requestId", p.get("requestId"),
                "decision", AetherCodeMethods.DECISION_ALLOW));
    }

    @Test
    void pendingPermissionCount_tracksLiveQueue(@TempDir Path cwd) {
        List<JsonRpcNotification> out = new ArrayList<>();
        AetherCodeMethods m = methodsWith(cwd, out);
        assertThat(m.pendingPermissionCount()).isEqualTo(0);
        m.askPermission("r", "file_write", Map.of(), "x", "medium")
                .whenComplete((d, e) -> {});
        m.askPermission("r", "file_edit", Map.of(), "x", "medium")
                .whenComplete((d, e) -> {});
        m.askPermission("r", "bash", Map.of(), "x", "high")
                .whenComplete((d, e) -> {});
        assertThat(m.pendingPermissionCount()).isEqualTo(3);
        // pendingCount in every notification tracks the
        // live queue length. First emit was 1, then 2, then 3.
        assertThat(((Map<?, ?>) out.get(0).params()).get("pendingCount")).isEqualTo(1);
        assertThat(((Map<?, ?>) out.get(1).params()).get("pendingCount")).isEqualTo(2);
        assertThat(((Map<?, ?>) out.get(2).params()).get("pendingCount")).isEqualTo(3);
        // Drain.
        for (JsonRpcNotification n : out) {
            String id = (String) ((Map<?, ?>) n.params()).get("requestId");
            m.permissionResponse(Map.of("requestId", id, "decision", AetherCodeMethods.DECISION_DENY));
        }
        assertThat(m.pendingPermissionCount()).isEqualTo(0);
    }

    @Test
    void permissionResponse_completesMatchingFuture(@TempDir Path cwd) throws Exception {
        List<JsonRpcNotification> out = new ArrayList<>();
        AetherCodeMethods m = methodsWith(cwd, out);
        CompletableFuture<AetherCodeMethods.PermissionDecision> fut = m.askPermission(
                "r", "bash", Map.of("command", "echo hi"), "demo", "high");
        String reqId = (String) ((Map<?, ?>) out.get(0).params()).get("requestId");
        Object resp = m.permissionResponse(Map.of(
                "requestId", reqId,
                "decision", AetherCodeMethods.DECISION_ALLOW,
                "reason", "ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) resp;
        assertThat(r.get("ok")).isEqualTo(true);
        AetherCodeMethods.PermissionDecision d = fut.get(1, TimeUnit.SECONDS);
        assertThat(d.decision()).isEqualTo(AetherCodeMethods.DECISION_ALLOW);
        assertThat(d.reason()).isEqualTo("ok");
        // Removed from the queue.
        assertThat(m.pendingPermissionCount()).isEqualTo(0);
    }

    @Test
    void permissionResponse_unknownRequestId_returnsOkFalse(@TempDir Path cwd) {
        List<JsonRpcNotification> out = new ArrayList<>();
        AetherCodeMethods m = methodsWith(cwd, out);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.permissionResponse(Map.of(
                "requestId", "perm-not-real",
                "decision", AetherCodeMethods.DECISION_ALLOW));
        // legacy logged + returned ok=false with reason.
        // R163 keeps that contract; the TUI should not treat a
        // stale reply as fatal.
        assertThat(r.get("ok")).isEqualTo(false);
    }

    @Test
    void getPermissionStatus_returnsShapeAndCount(@TempDir Path cwd) {
        List<JsonRpcNotification> out = new ArrayList<>();
        AetherCodeMethods m = methodsWith(cwd, out);
        m.askPermission("r1", "file_write", Map.of(), "a", "medium")
                .whenComplete((d, e) -> {});
        m.askPermission("r2", "bash", Map.of(), "b", "high")
                .whenComplete((d, e) -> {});
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) m.getPermissionStatus(Map.of());
        assertThat(s.get("ok")).isEqualTo(true);
        assertThat(s.get("pendingCount")).isEqualTo(2);
        assertThat(s).containsKey("asks");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> asks = (Map<String, Map<String, Object>>) s.get("asks");
        assertThat(asks).hasSize(2);
        // Each entry carries a requestId + completion state.
        for (Map<String, Object> v : asks.values()) {
            assertThat(v).containsKey("requestId");
            assertThat(v).containsKey("done");
            assertThat(v).containsKey("cancelled");
        }
        // Drain.
        for (JsonRpcNotification n : out) {
            String id = (String) ((Map<?, ?>) n.params()).get("requestId");
            m.permissionResponse(Map.of("requestId", id, "decision", AetherCodeMethods.DECISION_DENY));
        }
    }

    @Test
    void getPermissionStatus_emptyQueueIsShapeOnly(@TempDir Path cwd) {
        List<JsonRpcNotification> out = new ArrayList<>();
        AetherCodeMethods m = methodsWith(cwd, out);
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) m.getPermissionStatus(Map.of());
        assertThat(s.get("pendingCount")).isEqualTo(0);
        assertThat(s.get("asks")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> asks = (Map<String, Object>) s.get("asks");
        assertThat(asks).isEmpty();
    }

    @Test
    void pendingPermissionSnapshot_includesDoneFlag(@TempDir Path cwd) throws Exception {
        List<JsonRpcNotification> out = new ArrayList<>();
        AetherCodeMethods m = methodsWith(cwd, out);
        CompletableFuture<AetherCodeMethods.PermissionDecision> fut = m.askPermission(
                "r", "bash", Map.of(), "x", "high");
        String reqId = (String) ((Map<?, ?>) out.get(0).params()).get("requestId");
        m.permissionResponse(Map.of("requestId", reqId, "decision", AetherCodeMethods.DECISION_ALLOW));
        // The future is complete and the entry is removed from
        // the queue, so a fresh snapshot is empty.
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> snap =
                (Map<String, Map<String, Object>>) m.pendingPermissionSnapshot();
        assertThat(snap).isEmpty();
        fut.get(1, TimeUnit.SECONDS);
    }
}
