package org.aethercode.evals.sdk.bridge;

import org.aethercode.bridge.Blackboard;
import org.aethercode.bridge.InMemoryBlackboard;
import org.aethercode.bridge.ReconnectStrategy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-sdk-13: AetherCode Bridge / Swarm Interface conformance.
 *
 * <p>The {@code aethercode-bridge} module is the engine's gateway
 * to a peer process (or a swarm of teammates). The
 * {@link Blackboard} interface is the shared KV store; the
 * {@link ReconnectStrategy} drives the exponential backoff loop
 * when the bridge socket drops. R-AUDIT-SELF-IMPROVEMENT flagged
 * this as Tier-2 — a silent reconnect-loop bug would manifest as
 * "bridge flaky" with no obvious cause.</p>
 */
class SdkBridgeInterfaceTest {

    /* ---------------- Blackboard: InMemoryBlackboard ---------------- */

    @Test
    void inMemoryBlackboardStartsEmpty() {
        Blackboard bb = new InMemoryBlackboard();
        assertTrue(bb.keys().isEmpty());
        assertNull(bb.read("missing"));
        assertTrue(bb.snapshot().isEmpty());
    }

    @Test
    void inMemoryBlackboardWriteReadRoundTrip() {
        Blackboard bb = new InMemoryBlackboard();
        bb.write("k1", "v1");
        assertEquals("v1", bb.read("k1"));
        assertTrue(bb.keys().contains("k1"));
    }

    @Test
    void inMemoryBlackboardOverwriteReplaces() {
        Blackboard bb = new InMemoryBlackboard();
        bb.write("k", 1);
        bb.write("k", 2);
        assertEquals(2, bb.read("k"));
    }

    @Test
    void inMemoryBlackboardDeleteRemoves() {
        Blackboard bb = new InMemoryBlackboard();
        bb.write("k", "v");
        bb.delete("k");
        assertNull(bb.read("k"));
        assertFalse(bb.keys().contains("k"));
    }

    @Test
    void inMemoryBlackboardSnapshotIsUnmodifiableView() {
        Blackboard bb = new InMemoryBlackboard();
        bb.write("k", "v");
        Map<String, Object> snap = bb.snapshot();
        // External mutation must not affect the blackboard.
        assertThrows(UnsupportedOperationException.class,
                () -> snap.put("k2", "v2"));
    }

    @Test
    void inMemoryBlackboardFlushIsNoOp() {
        Blackboard bb = new InMemoryBlackboard();
        // The in-memory backend has no I/O; flush must not throw
        // and must not lose data.
        bb.write("k", "v");
        bb.flush();
        assertEquals("v", bb.read("k"));
    }

    @Test
    void inMemoryBlackboardConcurrentWrites() throws InterruptedException {
        // Concurrent use: 8 threads write 100 keys each; the
        // final state has 800 keys (no lost writes).
        Blackboard bb = new InMemoryBlackboard();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < 8; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    bb.write("k-" + threadId + "-" + i, "v" + i);
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        assertEquals(800, bb.keys().size());
    }

    /* ---------------- ReconnectStrategy: defaults ---------------- */

    @Test
    void reconnectStrategyDefaultsAreStable() {
        // The defaults are the API: changing them changes the
        // backoff curve that every bridge socket uses.
        assertEquals(1_000, ReconnectStrategy.DEFAULT_INITIAL_DELAY_MS);
        assertEquals(30_000, ReconnectStrategy.DEFAULT_MAX_DELAY_MS);
        assertEquals(8, ReconnectStrategy.DEFAULT_MAX_ATTEMPTS);
    }

    @Test
    void reconnectStrategyDefaultConstructor() {
        ReconnectStrategy r = new ReconnectStrategy();
        assertEquals(8, r.maxAttempts());
        assertEquals(0, r.attempts());
    }

    @Test
    void reconnectStrategyCustomParameters() {
        ReconnectStrategy r = new ReconnectStrategy(500, 5_000, 3);
        assertEquals(3, r.maxAttempts());
    }

    /* ---------------- ReconnectStrategy: backoff curve ---------------- */

    @Test
    void reconnectStrategyFirstDelayIsAtLeastInitial() {
        // The first delay (attempt 1) is the base × 2^0 = base.
        // With 25% jitter, the delay is in [base, base × 1.25).
        ReconnectStrategy r = new ReconnectStrategy(1_000, 30_000, 8);
        for (int trial = 0; trial < 20; trial++) {
            r.reset();
            int d = r.nextDelayMs();
            assertTrue(d >= 1_000 && d < 1_250,
                    "first delay should be in [1000, 1250), got " + d);
        }
    }

    @Test
    void reconnectStrategyBackoffGrows() {
        // After the first delay, the second is roughly 2× (with
        // jitter), capped at maxDelayMs.
        ReconnectStrategy r = new ReconnectStrategy(1_000, 30_000, 8);
        r.nextDelayMs(); // 1st
        int d2 = r.nextDelayMs(); // 2nd (2× base + jitter)
        assertTrue(d2 >= 1_500 && d2 < 2_500,
                "second delay ~ 2000ms, got " + d2);
    }

    @Test
    void reconnectStrategyCapsAtMaxDelay() {
        // After enough attempts, the base delay would exceed
        // maxDelayMs and gets capped.
        ReconnectStrategy r = new ReconnectStrategy(1_000, 4_000, 20);
        for (int i = 0; i < 10; i++) r.nextDelayMs();
        // Latest delay should be capped near maxDelayMs × 1.25.
        int last = r.nextDelayMs();
        assertTrue(last <= 5_000,
                "capped delay <= maxDelayMs × 1.25, got " + last);
    }

    @Test
    void reconnectStrategyGivesUpAfterMaxAttempts() {
        ReconnectStrategy r = new ReconnectStrategy(100, 1_000, 3);
        // 3 attempts succeed, 4th gives up.
        assertTrue(r.nextDelayMs() >= 0);
        assertTrue(r.nextDelayMs() >= 0);
        assertTrue(r.nextDelayMs() >= 0);
        assertEquals(-1, r.nextDelayMs(), "give up after maxAttempts");
    }

    @Test
    void reconnectStrategyOnGiveUpCallbackFires() {
        AtomicInteger giveUps = new AtomicInteger();
        ReconnectStrategy r = new ReconnectStrategy(100, 1_000, 1);
        r.onGiveUp(n -> giveUps.incrementAndGet());
        r.nextDelayMs(); // 1st attempt
        r.nextDelayMs(); // 2nd -> give up
        assertEquals(1, giveUps.get());
    }

    @Test
    void reconnectStrategyResetReturnsToZero() {
        ReconnectStrategy r = new ReconnectStrategy(100, 1_000, 3);
        r.nextDelayMs();
        r.nextDelayMs();
        assertEquals(2, r.attempts());
        r.reset();
        assertEquals(0, r.attempts());
    }

    /* ---------------- Blackboard semantics that the swarm relies on ---------------- */

    @Test
    void blackboardKeysIsASnapshot() {
        // keys() returns a snapshot — adding a key to the
        // blackboard later must not affect a previously-taken
        // snapshot.
        Blackboard bb = new InMemoryBlackboard();
        bb.write("a", 1);
        Set<String> beforeB = bb.keys();
        // Adding a new key after the snapshot was taken must not
        // leak into the old snapshot.
        bb.write("b", 2);
        assertFalse(beforeB.contains("b"),
                "earlier snapshot must not see later writes");
        // A fresh snapshot does see both keys.
        Set<String> fresh = bb.keys();
        assertTrue(fresh.contains("a"));
        assertTrue(fresh.contains("b"));
    }

    @Test
    void blackboardAcceptsNestedMapValue() {
        // A peer can post a structured payload.
        Blackboard bb = new InMemoryBlackboard();
        Map<String, Object> payload = new HashMap<>();
        payload.put("region", "us-east");
        payload.put("count", 42);
        bb.write("peer-1", payload);
        Object read = bb.read("peer-1");
        assertTrue(read instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> readMap = (Map<String, Object>) read;
        assertEquals("us-east", readMap.get("region"));
        assertNotNull(readMap.get("count"));
    }
}
