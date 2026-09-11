package org.aethercode.deepagents.selfimprove;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * end-to-end tests for
 * {@link CachingBankClient}. Each test wires the cache
 * around a hand-rolled {@link CountingBankClient}
 * (a {@link BankClient} subclass that counts delegate
 * calls and returns canned responses) so the tests are
 * deterministic and don't need a real bank server.
 */
class CachingBankClientTest {

    private CountingBankClient delegate;
    private CachingBankClient cached;

    @BeforeEach
    void setUp() {
        delegate = new CountingBankClient(URI.create("http://stub/"));
        cached = new CachingBankClient(delegate, 100, Duration.ofSeconds(30));
    }

    @Test
    void recallForCachesAndDoesNotResend() {
        delegate.cannedRecall = List.of(unit("u-1", "kind-a", "fix-a"));
        // First call → cache miss → delegate hit.
        List<Map<String, Object>> r1 = cached.recallFor("kind-a", 5);
        assertEquals(1, delegate.recallForCalls.get());
        assertEquals(1, r1.size());
        // Second call with same kind+n → cache hit.
        List<Map<String, Object>> r2 = cached.recallFor("kind-a", 5);
        assertEquals(1, delegate.recallForCalls.get(),
                "second call should hit cache, not delegate");
        assertEquals(r1.size(), r2.size());
    }

    @Test
    void differentKindOrNIsSeparateCacheEntry() {
        delegate.cannedRecall = List.of(unit("u-1", "kind-a", "fix-a"));
        cached.recallFor("kind-a", 5);
        cached.recallFor("kind-a", 10);  // different n
        cached.recallFor("kind-b", 5);   // different kind
        assertEquals(3, delegate.recallForCalls.get(),
                "different keys should not share cache");
    }

    @Test
    void recallAllKindsHasItsOwnCacheEntry() {
        delegate.cannedAllKinds = List.of(unit("u-1", "kind-a", "fix-a"));
        cached.recallAllKinds(20);
        cached.recallAllKinds(20);
        assertEquals(1, delegate.recallAllKindsCalls.get(),
                "second recallAllKinds should hit cache");
    }

    @Test
    void statsIsCachedSeparately() {
        delegate.cannedStats = Map.of("totalUnits", 7, "kinds", 2);
        cached.stats();
        cached.stats();
        assertEquals(1, delegate.statsCalls.get(),
                "second stats should hit cache");
    }

    @Test
    void touchInvalidatesAllCachedReads() {
        delegate.cannedRecall = List.of(unit("u-1", "kind-a", "fix-a"));
        delegate.cannedTouch = Map.of("id", "u-1", "uses", 1);
        // Prime both cache slots.
        cached.recallFor("kind-a", 5);
        cached.recallAllKinds(20);
        cached.stats();
        assertEquals(3, cached.cacheSize());
        // Write → invalidate.
        cached.touch("u-1");
        assertEquals(0, cached.cacheSize(),
                "touch should drop all cached reads");
        // Next read re-fetches.
        cached.recallFor("kind-a", 5);
        assertEquals(2, delegate.recallForCalls.get(),
                "post-invalidate recall should re-fetch");
    }

    @Test
    void recordOutcomeInvalidatesAllCachedReads() {
        delegate.cannedRecall = List.of(unit("u-1", "kind-a", "fix-a"));
        delegate.cannedRecord = Map.of("id", "u-1", "okCount", 1);
        cached.recallFor("kind-a", 5);
        assertEquals(1, cached.cacheSize());
        cached.recordOutcome("u-1", true);
        assertEquals(0, cached.cacheSize(),
                "recordOutcome should drop all cached reads");
    }

    @Test
    void lruEvictionDropsEldestWhenMaxSizeExceeded() {
        // maxSize=3, fill 4 distinct recallFor keys;
        // the eldest should be evicted.
        CachingBankClient small = new CachingBankClient(delegate, 3, Duration.ofSeconds(30));
        delegate.cannedRecall = List.of(unit("u-1", "kind", "fix"));
        small.recallFor("a", 1);
        small.recallFor("b", 1);
        small.recallFor("c", 1);
        assertEquals(3, small.cacheSize());
        small.recallFor("d", 1);
        // 4th insert pushes the eldest ("a") out.
        assertEquals(3, small.cacheSize(),
                "size should stay at maxSize after eviction");
        // "a" was evicted; re-fetching it goes to the delegate again.
        small.recallFor("a", 1);
        assertEquals(5, delegate.recallForCalls.get(),
                "evicted key should re-fetch from delegate");
    }

    @Test
    void ttlExpiryTriggersRefetch() throws Exception {
        // 50 ms TTL — well under the test's wall clock
        // budget so we can wait for expiry without
        // flakiness.
        CachingBankClient shortTtl = new CachingBankClient(delegate, 100, Duration.ofMillis(50));
        delegate.cannedRecall = List.of(unit("u-1", "kind", "fix"));
        shortTtl.recallFor("kind", 1);
        shortTtl.recallFor("kind", 1);
        assertEquals(1, delegate.recallForCalls.get());
        Thread.sleep(100);
        shortTtl.recallFor("kind", 1);
        assertEquals(2, delegate.recallForCalls.get(),
                "expired entry should re-fetch from delegate");
    }

    @Test
    void cacheReturnsDefensiveCopiesSoCallersCannotMutateCachedState() {
        // Important: callers that get a cached list and
        // mutate it should not affect subsequent cache
        // hits. Otherwise the cache silently turns into
        // shared mutable state.
        delegate.cannedRecall = new ArrayList<>();
        delegate.cannedRecall.add(unit("u-1", "kind", "fix"));
        List<Map<String, Object>> r1 = cached.recallFor("kind", 1);
        r1.clear();  // attempt to mutate the cached value
        List<Map<String, Object>> r2 = cached.recallFor("kind", 1);
        assertEquals(1, r2.size(),
                "mutating the first result should not affect the cache");
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    private static Map<String, Object> unit(String id, String kind, String fix) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("taskKind", kind);
        m.put("errorPattern", "e-" + id);
        m.put("fixStrategy", fix);
        m.put("example", "ex-" + id);
        m.put("utility", 0.5);
        m.put("uses", 0);
        m.put("okCount", 0);
        m.put("notOkCount", 0);
        m.put("createdAt", java.time.Instant.now().toString());
        return m;
    }

    /**
     * Hand-rolled {@link BankClient} subclass that returns
     * canned responses and counts delegate calls. Avoids
     * the need for a real bank server in cache tests.
     */
    static class CountingBankClient extends BankClient {
        final AtomicInteger recallForCalls = new AtomicInteger();
        final AtomicInteger recallAllKindsCalls = new AtomicInteger();
        final AtomicInteger touchCalls = new AtomicInteger();
        final AtomicInteger recordOutcomeCalls = new AtomicInteger();
        final AtomicInteger statsCalls = new AtomicInteger();
        List<Map<String, Object>> cannedRecall = List.of();
        List<Map<String, Object>> cannedAllKinds = List.of();
        Map<String, Object> cannedTouch = Map.of();
        Map<String, Object> cannedRecord = Map.of();
        Map<String, Object> cannedStats = Map.of();

        CountingBankClient(URI base) {
            super(base);
        }

        @Override
        public List<Map<String, Object>> recallFor(String kind, int n) {
            recallForCalls.incrementAndGet();
            return new ArrayList<>(cannedRecall);
        }

        @Override
        public List<Map<String, Object>> recallAllKinds(int n) {
            recallAllKindsCalls.incrementAndGet();
            return new ArrayList<>(cannedAllKinds);
        }

        @Override
        public Map<String, Object> touch(String id) {
            touchCalls.incrementAndGet();
            return new LinkedHashMap<>(cannedTouch);
        }

        @Override
        public Map<String, Object> recordOutcome(String id, boolean ok) {
            recordOutcomeCalls.incrementAndGet();
            return new LinkedHashMap<>(cannedRecord);
        }

        @Override
        public Map<String, Object> stats() {
            statsCalls.incrementAndGet();
            return new LinkedHashMap<>(cannedStats);
        }
    }
}
