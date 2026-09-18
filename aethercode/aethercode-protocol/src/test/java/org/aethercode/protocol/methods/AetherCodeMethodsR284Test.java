package org.aethercode.protocol.methods;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aethercode.core.app.AppState;
import org.aethercode.core.compact.SnapshotStore;
import org.aethercode.core.compact.SnapshotStore.Snapshot;
import org.aethercode.core.message.Message;
import org.aethercode.core.transcript.SessionStore;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R284: tests for {@code compact/listSnapshots} and
 * {@code compact/getSnapshot}. These are the RPCs the
 * desktop MessageList calls when the user clicks "View
 * original" on a summary message. The previous round's
 * SnapshotStoreTest covered the on-disk mechanics; this
 * round covers the wire contract — sessionId routing,
 * missing-session handling, and the full message-list
 * round-trip.
 *
 * <p>The tests build a real {@link AetherCodeEngine} +
 * {@link SnapshotStore} on a temp dir, swap them in via
 * the engine's setters, and call the handlers directly.
 */
class AetherCodeMethodsR284Test {

    @Test
    void compactListSnapshots_returnsAllForSession(@TempDir Path tmp)
            throws Exception {
        SessionStore ss = new SessionStore(tmp.resolve("sessions"));
        AetherCodeEngine engine = newEngine(tmp, ss, "sess-1");
        SnapshotStore snap = new SnapshotStore(tmp.resolve("snapshots"));
        engine.setSnapshotStore(snap);
        // pre-populate two snapshots on sess-1, one on a
        // different session
        seedSnapshot(snap, "sess-1", 0, "first summary");
        seedSnapshot(snap, "sess-1", 1, "second summary");
        seedSnapshot(snap, "sess-2", 0, "other session summary");
        AetherCodeMethods methods = newMethods(engine);
        Object resp = methods.compactListSnapshots(
                Map.of("sessionId", "sess-1"));
        Map<String, Object> body = asMap(resp);
        assertTrue((Boolean) body.get("ok"));
        assertEquals("sess-1", body.get("sessionId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) body.get("snapshots");
        assertEquals(2, rows.size());
        assertEquals(0L, ((Number) rows.get(0).get("compactionIndex")).longValue());
        assertEquals(1L, ((Number) rows.get(1).get("compactionIndex")).longValue());
        // the cross-session row MUST NOT leak
        for (Map<String, Object> row : rows) {
            assertEquals("sess-1",
                    ((String) rows.get(0).get("fileName")).split("__")[0]);
        }
    }

    @Test
    void compactListSnapshots_defaultsToActiveSession(@TempDir Path tmp)
            throws Exception {
        SessionStore ss = new SessionStore(tmp.resolve("sessions"));
        AetherCodeEngine engine = newEngine(tmp, ss, "active");
        SnapshotStore snap = new SnapshotStore(tmp.resolve("snapshots"));
        engine.setSnapshotStore(snap);
        seedSnapshot(snap, "active", 0, "the one");
        AetherCodeMethods methods = newMethods(engine);
        Object resp = methods.compactListSnapshots(new HashMap<>());
        Map<String, Object> body = asMap(resp);
        assertEquals("active", body.get("sessionId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) body.get("snapshots");
        assertEquals(1, rows.size());
    }

    @Test
    void compactListSnapshots_unknownSessionReturnsEmpty(@TempDir Path tmp)
            throws Exception {
        SessionStore ss = new SessionStore(tmp.resolve("sessions"));
        AetherCodeEngine engine = newEngine(tmp, ss, "active");
        SnapshotStore snap = new SnapshotStore(tmp.resolve("snapshots"));
        engine.setSnapshotStore(snap);
        AetherCodeMethods methods = newMethods(engine);
        Object resp = methods.compactListSnapshots(
                Map.of("sessionId", "never-saved"));
        Map<String, Object> body = asMap(resp);
        assertTrue((Boolean) body.get("ok"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) body.get("snapshots");
        assertEquals(0, rows.size());
    }

    @Test
    void compactListSnapshots_unwiredStoreReturnsEmpty(@TempDir Path tmp)
            throws Exception {
        // No snapshotStore on the engine. The RPC should
        // NOT crash; the renderer relies on an empty
        // snapshots[] to hide the "View original" chip.
        SessionStore ss = new SessionStore(tmp.resolve("sessions"));
        AetherCodeEngine engine = newEngine(tmp, ss, "active");
        AetherCodeMethods methods = newMethods(engine);
        Object resp = methods.compactListSnapshots(
                Map.of("sessionId", "active"));
        Map<String, Object> body = asMap(resp);
        assertTrue((Boolean) body.get("ok"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) body.get("snapshots");
        assertEquals(0, rows.size());
    }

    @Test
    void compactGetSnapshot_returnsFullMessageList(@TempDir Path tmp)
            throws Exception {
        SessionStore ss = new SessionStore(tmp.resolve("sessions"));
        AetherCodeEngine engine = newEngine(tmp, ss, "sess-1");
        SnapshotStore snap = new SnapshotStore(tmp.resolve("snapshots"));
        engine.setSnapshotStore(snap);
        // seed with three messages so the response carries a
        // non-trivial messages array.
        Message m0 = Message.userText("hello");
        Message m1 = Message.userText("world");
        Message m2 = Message.userText("foo bar baz");
        Snapshot persisted = snap.saveForSession("sess-1", 3, "sum", List.of(m0, m1, m2));
        AetherCodeMethods methods = newMethods(engine);
        Object resp = methods.compactGetSnapshot(Map.of(
                "sessionId", "sess-1",
                "compactionIndex", persisted.compactionIndex()));
        Map<String, Object> body = asMap(resp);
        assertTrue((Boolean) body.get("ok"));
        Map<String, Object> snapBody = asMap(body.get("snapshot"));
        assertEquals(0L, ((Number) snapBody.get("compactionIndex")).longValue());
        assertEquals(3, snapBody.get("originalMessageCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> msgs =
                (List<Map<String, Object>>) snapBody.get("messages");
        assertEquals(3, msgs.size());
        // Message.toMap nests text under content[0].text
        // (ContentBlock.TextBlock). Verify via that path
        // rather than peeking at Message internals.
        assertEquals("hello", firstTextOf(msgs.get(0)));
        assertEquals("world", firstTextOf(msgs.get(1)));
        assertEquals("foo bar baz", firstTextOf(msgs.get(2)));
        assertNotNull(snapBody.get("summary"));
    }

    @Test
    void compactGetSnapshot_unknownIndexReturnsNotFound(@TempDir Path tmp)
            throws Exception {
        SessionStore ss = new SessionStore(tmp.resolve("sessions"));
        AetherCodeEngine engine = newEngine(tmp, ss, "sess-1");
        SnapshotStore snap = new SnapshotStore(tmp.resolve("snapshots"));
        engine.setSnapshotStore(snap);
        AetherCodeMethods methods = newMethods(engine);
        Object resp = methods.compactGetSnapshot(Map.of(
                "sessionId", "sess-1",
                "compactionIndex", 99));
        Map<String, Object> body = asMap(resp);
        assertFalse((Boolean) body.get("ok"));
        assertEquals("not-found", body.get("error"));
    }

    @Test
    void compactGetSnapshot_summaryPreviewIsTruncated(@TempDir Path tmp)
            throws Exception {
        SessionStore ss = new SessionStore(tmp.resolve("sessions"));
        AetherCodeEngine engine = newEngine(tmp, ss, "sess-1");
        SnapshotStore snap = new SnapshotStore(tmp.resolve("snapshots"));
        engine.setSnapshotStore(snap);
        // 500-char summary — listSnapshots should clip at 200
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) big.append('x');
        snap.saveForSession("sess-1", 1, big.toString(), List.of());
        AetherCodeMethods methods = newMethods(engine);
        Object resp = methods.compactListSnapshots(
                Map.of("sessionId", "sess-1"));
        Map<String, Object> body = asMap(resp);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) body.get("snapshots");
        String preview = (String) rows.get(0).get("summary");
        assertTrue(preview.length() <= 203, "preview truncated to ≤200 chars + ellipsis");
        assertTrue(preview.endsWith("…"));
    }

    // --- helpers ---

    private static AetherCodeEngine newEngine(Path tmp, SessionStore ss, String sessionId)
            throws Exception {
        return new AetherCodeEngine.Builder()
                .cwd(tmp.resolve("cwd"))
                .sessionId(sessionId)
                .sessionStore(ss)
                .build();
    }

    /** The RPM wrapper of the engine isn't on the AetherCodeMethods
     *  test path — call the handlers directly. The handlers we
     *  exercise here only read {@code engine.snapshotStore()} and
     *  {@code engine.appState().sessionId()}; neither hits the
     *  LLM client, so a {@code null} notifier is fine. */
    private static AetherCodeMethods newMethods(AetherCodeEngine engine) {
        return new AetherCodeMethods(engine, (org.aethercode.protocol.jsonrpc.JsonRpcNotification n) -> {});
    }

    private static void seedSnapshot(
            SnapshotStore store, String sessionId,
            long expectedIndex, String summary) {
        // Mirror the real engine path: save a snapshot then
        // delete-then-resave doesn't fit here — just save and
        // assert the index matches what we expect.
        Snapshot snap = store.saveForSession(
                sessionId, 5, summary,
                List.of(Message.userText("pre-compact")));
        assertEquals(expectedIndex, snap.compactionIndex(),
                "monotonic index for " + s());
    }

    private static String s() { return "test"; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    /** Walk the message map's content[0].text. The wire
     *  shape (set by Message.toMap) is:
     *    {role, content: [{type, text}], ...} */
    private static String firstTextOf(Map<String, Object> msg) {
        Object content = msg.get("content");
        if (!(content instanceof List<?> list) || list.isEmpty()) return null;
        Object block = list.get(0);
        if (!(block instanceof Map<?, ?> bm)) return null;
        Object text = bm instanceof Map ? ((Map<String, Object>) bm).get("text") : null;
        return text == null ? null : text.toString();
    }
}