package org.aethercode.evals.capability.state;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-eval-5: State Tracking & Causal Reasoning capability suite.
 *
 * <p>Covers Survey on Evaluation of LLM-based Agents (2503.16416)
 * §2.1 + arXiv:2506.11102 "Evolutionary Perspectives" — five
 * sub-abilities:</p>
 *
 * <ul>
 *   <li>Watchdog state — detect silent / stuck runs and fire a
 *       callback (mirrors AetherCode's
 *       {@code org.aethercode.sdk.Watchdog})</li>
 *   <li>Circuit breaker state — CLOSED / OPEN / HALF_OPEN
 *       transitions under load (mirrors
 *       {@code org.aethercode.sdk.CircuitBreaker})</li>
 *   <li>Backpressure / rate-limit state — token bucket / sliding
 *       window</li>
 *   <li>Session state machine — turn-based session with
 *       deterministic transitions</li>
 *   <li>Causal chain reasoning — building + auditing a causal
 *       graph; identifying the root cause of an observed effect</li>
 *   <li>Retry policy with backoff state — exponential backoff
 *       + jitter, bounded retries</li>
 * </ul>
 */
class StateTrackingCapabilityTest {

    /* --------------------- Watchdog state machine --------------------- */

    /** A watchdog that polls {@code lastEventTimeMs} every
     *  {@code pollMs}; fires the timeout handler when the gap
     *  exceeds {@code timeoutMs}. */
    public static final class Watchdog {
        public interface TimeoutHandler { void onTimeout(long silenceMs); }
        private final LongSupplier clock;
        private final TimeoutHandler handler;
        private final long pollMs;
        private final long timeoutMs;
        private final AtomicLong lastEvent = new AtomicLong();
        private boolean tripped = false;

        public Watchdog(LongSupplier clock, TimeoutHandler handler,
                        long pollMs, long timeoutMs) {
            if (clock == null || handler == null) {
                throw new IllegalArgumentException("clock and handler required");
            }
            if (pollMs < 1 || timeoutMs < pollMs) {
                throw new IllegalArgumentException("invalid poll/timeout");
            }
            this.clock = clock;
            this.handler = handler;
            this.pollMs = pollMs;
            this.timeoutMs = timeoutMs;
            this.lastEvent.set(clock.getAsLong());
        }

        public void kick() { lastEvent.set(clock.getAsLong()); }
        public boolean isTripped() { return tripped; }

        public void check() {
            long silence = clock.getAsLong() - lastEvent.get();
            if (silence >= timeoutMs && !tripped) {
                tripped = true;
                handler.onTimeout(silence);
            }
        }
    }

    /* --------------------- Circuit breaker state machine --------------------- */

    public enum BreakerState { CLOSED, OPEN, HALF_OPEN }

    public static final class CircuitBreaker {
        private final int threshold;
        private final long cooldownMs;
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicLong openedAt = new AtomicLong(0);
        private volatile BreakerState state = BreakerState.CLOSED;

        public CircuitBreaker(int threshold, long cooldownMs) {
            if (threshold < 1) throw new IllegalArgumentException("threshold");
            if (cooldownMs < 0) throw new IllegalArgumentException("cooldownMs");
            this.threshold = threshold;
            this.cooldownMs = cooldownMs;
        }

        public synchronized boolean isOpen(long nowMs) {
            if (state == BreakerState.CLOSED) return false;
            if (state == BreakerState.HALF_OPEN) return false;
            // OPEN: allow if cooldown has passed.
            if (nowMs - openedAt.get() >= cooldownMs) {
                state = BreakerState.HALF_OPEN;
                return false;
            }
            return true;
        }

        public synchronized void recordSuccess() {
            failures.set(0);
            state = BreakerState.CLOSED;
        }

        public synchronized void recordFailure(long nowMs) {
            int n = failures.incrementAndGet();
            if (n >= threshold || state == BreakerState.HALF_OPEN) {
                openedAt.set(nowMs);
                state = BreakerState.OPEN;
            }
        }

        public BreakerState state() { return state; }
        public int failures() { return failures.get(); }
    }

    /* --------------------- Backpressure (token bucket) --------------------- */

    public static final class TokenBucket {
        private final long capacity;
        private final long refillPerMs;
        private long tokens;
        private long lastRefillMs;

        public TokenBucket(long capacity, long refillPerMs) {
            if (capacity < 1) throw new IllegalArgumentException("capacity");
            if (refillPerMs < 0) throw new IllegalArgumentException("refillPerMs");
            this.capacity = capacity;
            this.refillPerMs = refillPerMs;
            this.tokens = capacity;
            this.lastRefillMs = 0;
        }

        public synchronized boolean tryAcquire(long n, long nowMs) {
            refill(nowMs);
            if (tokens >= n) {
                tokens -= n;
                return true;
            }
            return false;
        }

        private void refill(long nowMs) {
            long delta = nowMs - lastRefillMs;
            if (delta > 0) {
                tokens = Math.min(capacity, tokens + delta * refillPerMs);
                lastRefillMs = nowMs;
            }
        }

        public long tokens() { return tokens; }
    }

    /* --------------------- Session state machine --------------------- */

    public enum SessionState { NEW, ACTIVE, PAUSED, RESUMED, CLOSED }

    public static final class Session {
        private final String id;
        private SessionState state = SessionState.NEW;
        private final List<String> turns = new ArrayList<>();
        private final long createdAt;

        public Session(String id, long createdAt) {
            this.id = Objects.requireNonNull(id);
            this.createdAt = createdAt;
        }

        public synchronized SessionState state() { return state; }

        public synchronized void activate() {
            if (state == SessionState.NEW) state = SessionState.ACTIVE;
            else throw new IllegalStateException("cannot activate from " + state);
        }

        public synchronized void pause() {
            if (state != SessionState.ACTIVE) {
                throw new IllegalStateException("can only pause ACTIVE");
            }
            state = SessionState.PAUSED;
        }

        public synchronized void resume() {
            if (state != SessionState.PAUSED) {
                throw new IllegalStateException("can only resume PAUSED");
            }
            state = SessionState.RESUMED;
        }

        public synchronized void close() {
            state = SessionState.CLOSED;
        }

        public synchronized void recordTurn(String turn) {
            if (state != SessionState.ACTIVE && state != SessionState.RESUMED) {
                throw new IllegalStateException("turns require ACTIVE or RESUMED, got " + state);
            }
            turns.add(turn);
        }

        public List<String> turns() { return List.copyOf(turns); }
        public String id() { return id; }
    }

    /* --------------------- Causal graph --------------------- */

    public static final class CausalGraph {
        private final Map<String, Set<String>> causes = new LinkedHashMap<>();
        private final Map<String, Set<String>> effects = new LinkedHashMap<>();

        public synchronized void addEdge(String cause, String effect) {
            causes.computeIfAbsent(effect, k -> new LinkedHashSet<>()).add(cause);
            effects.computeIfAbsent(cause, k -> new LinkedHashSet<>()).add(effect);
            causes.computeIfAbsent(cause, k -> new LinkedHashSet<>());
            effects.computeIfAbsent(effect, k -> new LinkedHashSet<>());
        }

        public synchronized Set<String> causesOf(String effect) {
            return Set.copyOf(causes.getOrDefault(effect, Set.of()));
        }

        public synchronized Set<String> effectsOf(String cause) {
            return Set.copyOf(effects.getOrDefault(cause, Set.of()));
        }

        /** Topological order via Kahn's algorithm. Throws on cycle. */
        public synchronized List<String> topoOrder() {
            Map<String, Integer> inDeg = new LinkedHashMap<>();
            for (String n : allNodes()) inDeg.putIfAbsent(n, 0);
            for (String effect : causes.keySet()) {
                for (String cause : causes.get(effect)) {
                    inDeg.merge(effect, 1, Integer::sum);
                }
            }
            Deque<String> queue = new ArrayDeque<>();
            for (var e : inDeg.entrySet()) if (e.getValue() == 0) queue.add(e.getKey());
            List<String> order = new ArrayList<>();
            while (!queue.isEmpty()) {
                String n = queue.poll();
                order.add(n);
                for (String eff : effects.getOrDefault(n, Set.of())) {
                    if (inDeg.merge(eff, -1, Integer::sum) == 0) queue.add(eff);
                }
            }
            if (order.size() != allNodes().size()) {
                throw new IllegalStateException("cycle");
            }
            return order;
        }

        /** All root causes of {@code effect} (transitive). */
        public synchronized Set<String> rootCausesOf(String effect) {
            Set<String> visited = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            for (String c : causesOf(effect)) stack.push(c);
            while (!stack.isEmpty()) {
                String c = stack.pop();
                if (visited.add(c)) {
                    for (String cc : causesOf(c)) stack.push(cc);
                }
            }
            return visited;
        }

        public Set<String> allNodes() {
            Set<String> n = new LinkedHashSet<>();
            n.addAll(causes.keySet());
            n.addAll(effects.keySet());
            return n;
        }
    }

    /* --------------------- Retry policy with backoff --------------------- */

    public record RetryDecision(boolean shouldRetry, long delayMs) {}

    public static final class RetryPolicy {
        private final int maxRetries;
        private final long baseMs;
        private final long maxMs;
        private final double multiplier;

        public RetryPolicy(int maxRetries, long baseMs, long maxMs, double multiplier) {
            if (maxRetries < 0) throw new IllegalArgumentException("maxRetries");
            this.maxRetries = maxRetries;
            this.baseMs = Math.max(1, baseMs);
            this.maxMs = Math.max(this.baseMs, maxMs);
            this.multiplier = multiplier < 1 ? 1.0 : multiplier;
        }

        public RetryDecision shouldRetry(int attempt) {
            if (attempt >= maxRetries) {
                return new RetryDecision(false, 0);
            }
            // Exponential backoff: base * multiplier^attempt, capped.
            long delay = (long) Math.min(maxMs, baseMs * Math.pow(multiplier, attempt));
            return new RetryDecision(true, delay);
        }
    }

    /* --------------------- Watchdog tests --------------------- */

    @Test
    void watchdogFiresWhenSilenceExceedsTimeout() {
        AtomicLong clock = new AtomicLong(0);
        AtomicInteger tripCount = new AtomicInteger();
        Watchdog wd = new Watchdog(clock::get,
                silence -> tripCount.incrementAndGet(),
                100, 1000);
        // Simulate 1.5 seconds of silence.
        clock.set(1500);
        wd.check();
        assertEquals(1, tripCount.get());
        assertTrue(wd.isTripped());
    }

    @Test
    void watchdogDoesNotFireBelowTimeout() {
        AtomicLong clock = new AtomicLong(0);
        AtomicInteger tripCount = new AtomicInteger();
        Watchdog wd = new Watchdog(clock::get, silence -> tripCount.incrementAndGet(),
                100, 1000);
        // 500 ms of silence (below 1000 ms threshold).
        clock.set(500);
        wd.check();
        assertEquals(0, tripCount.get());
        assertFalse(wd.isTripped());
    }

    @Test
    void watchdogKickResetsTheTimer() {
        AtomicLong clock = new AtomicLong(0);
        AtomicInteger tripCount = new AtomicInteger();
        Watchdog wd = new Watchdog(clock::get, silence -> tripCount.incrementAndGet(),
                100, 1000);
        // Run for 1.5 seconds, kick every 500ms.
        for (long t = 500; t <= 1500; t += 500) {
            clock.set(t);
            wd.kick();
            wd.check();
        }
        assertEquals(0, tripCount.get(),
                "regular kicks should keep the watchdog from tripping");
    }

    /* --------------------- Circuit breaker tests --------------------- */

    @Test
    void circuitBreakerTripsAfterThresholdFailures() {
        CircuitBreaker cb = new CircuitBreaker(3, 1000);
        assertEquals(BreakerState.CLOSED, cb.state());
        cb.recordFailure(0);
        cb.recordFailure(0);
        assertEquals(BreakerState.CLOSED, cb.state());
        cb.recordFailure(0);
        assertEquals(BreakerState.OPEN, cb.state());
    }

    @Test
    void circuitBreakerRecoversAfterCooldown() {
        CircuitBreaker cb = new CircuitBreaker(2, 1000);
        cb.recordFailure(0);
        cb.recordFailure(0);
        assertEquals(BreakerState.OPEN, cb.state());
        assertTrue(cb.isOpen(500), "should be open during cooldown");
        // After cooldown, transitions to HALF_OPEN (isOpen returns false).
        assertFalse(cb.isOpen(1500), "should allow trial after cooldown");
        assertEquals(BreakerState.HALF_OPEN, cb.state());
    }

    @Test
    void circuitBreakerResetsOnSuccess() {
        CircuitBreaker cb = new CircuitBreaker(2, 1000);
        cb.recordFailure(0);
        cb.recordFailure(0);
        assertEquals(BreakerState.OPEN, cb.state());
        cb.recordSuccess();
        assertEquals(BreakerState.CLOSED, cb.state());
        assertEquals(0, cb.failures());
    }

    @Test
    void circuitBreakerRejectsInvalidArgs() {
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(1, -1));
    }

    /* --------------------- Backpressure / token bucket --------------------- */

    @Test
    void tokenBucketStartsFull() {
        TokenBucket b = new TokenBucket(10, 1);
        assertTrue(b.tryAcquire(5, 0));
        assertEquals(5, b.tokens());
    }

    @Test
    void tokenBucketRefillsOverTime() {
        TokenBucket b = new TokenBucket(10, 1);
        b.tryAcquire(10, 0);
        assertFalse(b.tryAcquire(1, 0));
        // 5 ms later, 5 tokens refilled.
        assertTrue(b.tryAcquire(5, 5));
    }

    @Test
    void tokenBucketCapsAtCapacity() {
        TokenBucket b = new TokenBucket(10, 1);
        // Start: tokens at capacity.
        assertEquals(10, b.tokens());
        // Wait a long time; tokens should not exceed capacity.
        b.tryAcquire(1, 1_000_000);
        // After draining 1, the bucket has 9 — capped at 10 even
        // after a 1M-ms wait.
        assertEquals(9, b.tokens());
    }

    /* --------------------- Session state machine --------------------- */

    @Test
    void sessionTransitionsNewToActiveToClosed() {
        Session s = new Session("s1", 0);
        assertEquals(SessionState.NEW, s.state());
        s.activate();
        assertEquals(SessionState.ACTIVE, s.state());
        s.close();
        assertEquals(SessionState.CLOSED, s.state());
    }

    @Test
    void sessionPauseResumeRoundTrip() {
        Session s = new Session("s1", 0);
        s.activate();
        s.pause();
        assertEquals(SessionState.PAUSED, s.state());
        s.resume();
        assertEquals(SessionState.RESUMED, s.state());
    }

    @Test
    void sessionTurnsRequireActiveOrResumed() {
        Session s = new Session("s1", 0);
        assertThrows(IllegalStateException.class, () -> s.recordTurn("hi"));
        s.activate();
        s.recordTurn("turn-1");
        s.pause();
        assertThrows(IllegalStateException.class, () -> s.recordTurn("after-pause"));
        s.resume();
        s.recordTurn("turn-2");
        assertEquals(List.of("turn-1", "turn-2"), s.turns());
    }

    @Test
    void sessionPauseOnlyFromActive() {
        Session s = new Session("s1", 0);
        assertThrows(IllegalStateException.class, () -> s.pause());
        s.activate();
        s.pause();
        // Cannot pause again from PAUSED.
        assertThrows(IllegalStateException.class, () -> s.pause());
    }

    /* --------------------- Causal graph tests --------------------- */

    @Test
    void causalGraphStoresEdges() {
        CausalGraph g = new CausalGraph();
        g.addEdge("A", "B");
        g.addEdge("A", "C");
        g.addEdge("B", "D");
        assertEquals(Set.of("A"), g.causesOf("B"));
        // D's direct cause is B (the only edge that ends at D);
        // A is a transitive cause, not a direct one.
        assertEquals(Set.of("B"), g.causesOf("D"));
        assertEquals(Set.of("B", "C"), g.effectsOf("A"));
    }

    @Test
    void causalGraphComputesTopoOrder() {
        CausalGraph g = new CausalGraph();
        g.addEdge("login", "session");
        g.addEdge("session", "request");
        g.addEdge("request", "response");
        List<String> order = g.topoOrder();
        assertEquals("login", order.get(0));
        assertEquals("response", order.get(3));
    }

    @Test
    void causalGraphRejectsCycles() {
        CausalGraph g = new CausalGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "A");
        assertThrows(IllegalStateException.class, g::topoOrder);
    }

    @Test
    void rootCauseAnalysisWalksTransitively() {
        // login → session → request → 500
        // The "500" has transitive root causes: login, session, request.
        CausalGraph g = new CausalGraph();
        g.addEdge("login", "session");
        g.addEdge("session", "request");
        g.addEdge("request", "500");
        Set<String> roots = g.rootCausesOf("500");
        assertTrue(roots.contains("login"));
        assertTrue(roots.contains("session"));
        assertTrue(roots.contains("request"));
        assertEquals(3, roots.size());
    }

    @Test
    void causalGraphIdentifiesDirectRootCause() {
        // For an effect with a single direct cause, the root cause
        // is that single direct cause.
        CausalGraph g = new CausalGraph();
        g.addEdge("network_blip", "timeout");
        assertEquals(Set.of("network_blip"), g.rootCausesOf("timeout"));
    }

    @Test
    void causalChainAuditFindsLongestPath() {
        // A -> B -> C -> D -> E: longest path is 5.
        CausalGraph g = new CausalGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "C");
        g.addEdge("C", "D");
        g.addEdge("D", "E");
        // BFS from A to find depth.
        Map<String, Integer> depth = new LinkedHashMap<>();
        depth.put("A", 1);
        Deque<String> queue = new ArrayDeque<>();
        queue.add("A");
        while (!queue.isEmpty()) {
            String n = queue.poll();
            for (String eff : g.effectsOf(n)) {
                if (!depth.containsKey(eff) || depth.get(eff) < depth.get(n) + 1) {
                    depth.put(eff, depth.get(n) + 1);
                    queue.add(eff);
                }
            }
        }
        assertEquals(1, depth.get("A"));
        assertEquals(5, depth.get("E"));
    }

    /* --------------------- Retry policy tests --------------------- */

    @Test
    void retryPolicyExponentialBackoff() {
        RetryPolicy p = new RetryPolicy(5, 100, 10_000, 2.0);
        RetryDecision d1 = p.shouldRetry(0);
        RetryDecision d2 = p.shouldRetry(1);
        RetryDecision d3 = p.shouldRetry(2);
        assertTrue(d1.shouldRetry());
        assertTrue(d2.shouldRetry());
        assertTrue(d3.shouldRetry());
        assertTrue(d2.delayMs() > d1.delayMs());
        assertTrue(d3.delayMs() > d2.delayMs());
    }

    @Test
    void retryPolicyCapsAtMax() {
        RetryPolicy p = new RetryPolicy(10, 100, 1000, 2.0);
        for (int i = 0; i < 9; i++) {
            RetryDecision d = p.shouldRetry(i);
            assertTrue(d.shouldRetry());
            assertTrue(d.delayMs() <= 1000, "delay must be capped at max, got " + d.delayMs());
        }
    }

    @Test
    void retryPolicyGivesUpAfterMax() {
        RetryPolicy p = new RetryPolicy(3, 100, 1000, 2.0);
        RetryDecision d = p.shouldRetry(3);
        assertFalse(d.shouldRetry());
    }

    @Test
    void retryPolicyRejectsInvalidMultiplier() {
        // Multiplier < 1 is silently clamped to 1.0.
        RetryPolicy p = new RetryPolicy(3, 100, 1000, 0.5);
        RetryDecision d1 = p.shouldRetry(0);
        RetryDecision d2 = p.shouldRetry(1);
        assertEquals(d1.delayMs(), d2.delayMs(),
                "multiplier < 1 should be clamped to 1.0 (constant backoff)");
    }

    /* --------------------- End-to-end: stateful retry with circuit breaker --------------------- */

    @Test
    void retryLoopStopsWhenCircuitBreakerTrips() {
        CircuitBreaker cb = new CircuitBreaker(3, 1000);
        RetryPolicy rp = new RetryPolicy(10, 50, 500, 2.0);
        AtomicInteger attempts = new AtomicInteger();
        int maxAttempts = 10;
        for (int i = 0; i < maxAttempts; i++) {
            if (cb.isOpen(0)) break;
            attempts.incrementAndGet();
            cb.recordFailure(0);
        }
        assertTrue(attempts.get() <= 4,
                "should stop once the breaker trips, attempts was " + attempts.get());
        assertEquals(BreakerState.OPEN, cb.state());
    }

    @Test
    void stateTransitionsAreAllDeterministic() {
        // Run the same session scenario 100 times; the state
        // sequence must be identical every time.
        for (int i = 0; i < 100; i++) {
            Session s = new Session("s-" + i, 0);
            s.activate();
            s.recordTurn("t1");
            s.pause();
            s.resume();
            s.recordTurn("t2");
            s.close();
            assertEquals(List.of("t1", "t2"), s.turns());
        }
    }
}
