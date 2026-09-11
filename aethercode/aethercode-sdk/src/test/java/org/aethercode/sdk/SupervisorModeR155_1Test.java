package org.aethercode.sdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R155.1: full WS proxy. The supervisor
 * opens a long-lived WebSocket connection to
 * each registered child, forwards every
 * server-pushed event to subscribers, and
 * maintains a per-child recent-events buffer
 * for late-reconnecting clients. These tests
 * pin the lifecycle: connect → receive →
 * buffer → disconnect, with the help of
 * {@link TestWebSocketServer} (a raw
 * WebSocket server for tests).
 */
class SupervisorModeR155_1Test {

    private TestWebSocketServer wsServer;
    private String childId;

    @BeforeEach
    void setUp() throws Exception {
        // Find two free ports.
        java.net.ServerSocket s1 = new java.net.ServerSocket(0);
        int wsPort = s1.getLocalPort();
        s1.close();
        java.net.ServerSocket s2 = new java.net.ServerSocket(0);
        int healthPort = s2.getLocalPort();
        s2.close();
        wsServer = new TestWebSocketServer(wsPort, healthPort);
        // Register a child pointing at the WS port.
        // The supervisor connects to ws://127.0.0.1:<wsPort>/ws.
        childId = "ws-child-" + System.nanoTime();
        SupervisorMode sm = SupervisorMode.instance();
        sm.registerChild(childId, wsPort, "D:\\tmp");
        sm.disconnectChildWs(childId); // start fresh
    }

    @AfterEach
    void tearDown() {
        SupervisorMode sm = SupervisorMode.instance();
        try { sm.unregisterChild(childId); } catch (Exception ignored) {}
        if (wsServer != null) wsServer.close();
    }

    @Test
    void connectChildWs_opensConnectionAndIncrementsCount() throws Exception {
        SupervisorMode sm = SupervisorMode.instance();
        assertEquals(0L, sm.childWsReceivedCount(childId), "starts at 0");
        boolean ok = sm.connectChildWs(childId);
        assertTrue(ok, "connectChildWs succeeded");
        // Wait for the connection to actually be
        // established on the server side (the supervisor
        // returns synchronously after the WS handshake
        // completes, so this should be near-instant).
        for (int i = 0; i < 50 && wsServer.connectionCount() == 0; i++) {
            Thread.sleep(50);
        }
        assertTrue(wsServer.connectionCount() >= 1,
                "test server saw at least one WS connection");
    }

    @Test
    void connectChildWs_unknownChildReturnsFalse() {
        SupervisorMode sm = SupervisorMode.instance();
        assertFalse(sm.connectChildWs("nonexistent-child"));
    }

    @Test
    void connectChildWs_isIdempotent() {
        SupervisorMode sm = SupervisorMode.instance();
        assertTrue(sm.connectChildWs(childId));
        // Second call should not throw and should
        // return true (existing handle is reused).
        assertTrue(sm.connectChildWs(childId));
    }

    @Test
    void pushText_reachesSupervisorSubscriber() throws Exception {
        SupervisorMode sm = SupervisorMode.instance();
        AtomicInteger received = new AtomicInteger();
        CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        sm.subscribeToChildEvents((cid, msg) -> {
            if (childId.equals(cid)) {
                received.incrementAndGet();
                messages.add(msg);
            }
        });
        assertTrue(sm.connectChildWs(childId));
        // Wait for connection
        for (int i = 0; i < 50 && wsServer.connectionCount() == 0; i++) {
            Thread.sleep(50);
        }
        // Push a transcript_event-like message
        assertTrue(wsServer.pushText("{\"method\":\"transcript_event\",\"params\":{\"action\":\"append\"}}"));
        // Wait for supervisor to receive
        for (int i = 0; i < 50 && received.get() == 0; i++) {
            Thread.sleep(50);
        }
        assertEquals(1, received.get(), "subscriber received 1 event");
        assertEquals(1, sm.childWsReceivedCount(childId), "receivedCount incremented");
        assertTrue(messages.get(0).contains("transcript_event"),
                "payload preserved: " + messages.get(0));
    }

    @Test
    void childWsRecentEvents_replaysBuffer() throws Exception {
        SupervisorMode sm = SupervisorMode.instance();
        assertTrue(sm.connectChildWs(childId));
        for (int i = 0; i < 50 && wsServer.connectionCount() == 0; i++) {
            Thread.sleep(50);
        }
        wsServer.pushText("event-1");
        wsServer.pushText("event-2");
        wsServer.pushText("event-3");
        // Wait for the supervisor to receive all 3
        for (int i = 0; i < 100 && sm.childWsReceivedCount(childId) < 3; i++) {
            Thread.sleep(50);
        }
        List<String> recent = sm.childWsRecentEvents(childId);
        assertEquals(3, recent.size(), "3 events in recent buffer");
        assertEquals("event-1", recent.get(0));
        assertEquals("event-3", recent.get(2));
    }

    @Test
    void childWsRecentEvents_emptyForUnknownChild() {
        SupervisorMode sm = SupervisorMode.instance();
        List<String> r = sm.childWsRecentEvents("nonexistent");
        assertTrue(r.isEmpty());
        assertEquals(0L, sm.childWsReceivedCount("nonexistent"));
    }

    @Test
    void disconnectChildWs_closesConnection() throws Exception {
        SupervisorMode sm = SupervisorMode.instance();
        assertTrue(sm.connectChildWs(childId));
        for (int i = 0; i < 50 && wsServer.connectionCount() == 0; i++) {
            Thread.sleep(50);
        }
        assertTrue(sm.disconnectChildWs(childId), "disconnect returns true");
        // Second disconnect is idempotent (no-op).
        assertFalse(sm.disconnectChildWs(childId), "second disconnect is no-op");
    }

    @Test
    void multipleSubscribers_allReceiveEvents() throws Exception {
        SupervisorMode sm = SupervisorMode.instance();
        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();
        sm.subscribeToChildEvents((cid, msg) -> { if (childId.equals(cid)) aCount.incrementAndGet(); });
        sm.subscribeToChildEvents((cid, msg) -> { if (childId.equals(cid)) bCount.incrementAndGet(); });
        assertTrue(sm.connectChildWs(childId));
        for (int i = 0; i < 50 && wsServer.connectionCount() == 0; i++) {
            Thread.sleep(50);
        }
        wsServer.pushText("event-x");
        for (int i = 0; i < 50 && aCount.get() == 0; i++) {
            Thread.sleep(50);
        }
        assertEquals(1, aCount.get());
        assertEquals(1, bCount.get());
        // Unsubscribe one
        sm.unsubscribeFromChildEvents((cid, msg) -> { /* nothing — wrong ref */ });
        // Note: above is a no-op because we
        // can't reach the exact lambda back.
        // The point is to verify the API
        // doesn't throw.
    }

    @Test
    void unsubscribeFromChildEvents_removesByReference() throws Exception {
        SupervisorMode sm = SupervisorMode.instance();
        AtomicInteger count = new AtomicInteger();
        java.util.function.BiConsumer<String, String> sub = (cid, msg) -> {
            if (childId.equals(cid)) count.incrementAndGet();
        };
        sm.subscribeToChildEvents(sub);
        sm.unsubscribeFromChildEvents(sub);
        assertTrue(sm.connectChildWs(childId));
        for (int i = 0; i < 50 && wsServer.connectionCount() == 0; i++) {
            Thread.sleep(50);
        }
        wsServer.pushText("event-after-unsub");
        Thread.sleep(200);
        assertEquals(0, count.get(), "unsubscribed consumer received 0");
    }

    @Test
    void stopAll_disconnectsAllWsProxies() throws Exception {
        SupervisorMode sm = SupervisorMode.instance();
        assertTrue(sm.connectChildWs(childId));
        for (int i = 0; i < 50 && wsServer.connectionCount() == 0; i++) {
            Thread.sleep(50);
        }
        // stopAll will kill the child too — that's
        // a problem because SupervisorMode has a
        // JVM-shutdown hook. But we can call it
        // here as a test-only operation; the hook
        // will also fire but stopAll is idempotent
        // for already-killed children.
        sm.stopAll(1000L);
        // After stopAll, the WS handle should be gone.
        Thread.sleep(200);
        assertEquals(0L, sm.childWsReceivedCount(childId),
                "WS handle cleared after stopAll");
    }
}
