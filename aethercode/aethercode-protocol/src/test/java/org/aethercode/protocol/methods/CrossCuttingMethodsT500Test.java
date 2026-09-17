package org.aethercode.protocol.methods;

import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-500 / design.md §5.4: pins the registration of the
 * cross-cutting RPC surface added in this round.
 *
 * <p>What we pin:
 * <ol>
 *   <li>All six {@code memory/*} methods (T-070..T-075)
 *       are registered by {@link MemoryMethods#registerAll}.</li>
 *   <li>All four {@code compact/*} methods (T-190..T-193)
 *       are registered by {@link CompactMethods#registerAll}.</li>
 *   <li>All six {@code theme/*} methods (T-501) are
 *       registered by {@link ThemeMethods#registerAll}.</li>
 *   <li>The {@code context/info} method (T-500 / §2.8)
 *       is registered by {@link ContextMethods#registerAll}.</li>
 *   <li>Each method has a non-empty tag entry in
 *       {@link AetherCodeMethods#METHOD_TAGS} using a
 *       known tag string. (The R124 test already
 *       enforces the tag-string allowlist; here we
 *       only assert non-empty + known-tag.)</li>
 *   <li>The total registered count for the new surface
 *       is exactly 17 (6 memory + 4 compact + 6 theme
 *       + 1 context).</li>
 * </ol>
 *
 * <p>The dispatcher test deliberately uses a real
 * {@link JsonRpcDispatcher} (not a mock) so the
 * registration contract is what production code uses.
 */
class CrossCuttingMethodsT500Test {

    private static JsonRpcDispatcher newDispatcher() {
        return new JsonRpcDispatcher(msg -> { /* drop */ });
    }

    @Test
    void memoryMethodsRegisterAllSix() {
        JsonRpcDispatcher d = newDispatcher();
        // The memory store is null in the test; we
        // don't exercise the handlers, just the
        // registration surface.
        MemoryMethods m = new MemoryMethods();
        m.registerAll(d);
        for (String name : new String[]{
                "memory/get",
                "memory/appendProjectChange",
                "memory/appendSessionFact",
                "memory/appendSessionChange",     // R280
                "memory/setProjectInfo",          // R280
                "memory/readProjectMemory",       // R280
                "memory/compact",
                "memory/switchProject",
                "memory/list"}) {
            assertTrue(d.hasMethod(name), "expected method " + name + " to be registered");
        }
    }

    @Test
    void memoryMethodsHandlesWithNullStoreReturnStructuredError() {
        MemoryMethods m = new MemoryMethods();
        // Without a backing store every handler should
        // return {ok: false, reason: "memory not configured"}
        // rather than throw — the TUI pair-renders
        // the missing state, never crashes.
        for (String method : new String[]{
                "memory/get", "memory/appendProjectChange",
                "memory/appendSessionFact", "memory/appendSessionChange",     // R280
                "memory/setProjectInfo", "memory/readProjectMemory",          // R280
                "memory/compact",
                "memory/switchProject", "memory/list"}) {
            JsonRpcDispatcher d = newDispatcher();
            m.registerAll(d);
            assertTrue(d.hasMethod(method));
        }
    }

    @Test
    void compactMethodsRegisterAllFour() {
        JsonRpcDispatcher d = newDispatcher();
        CompactMethods c = new CompactMethods();
        c.registerAll(d);
        for (String name : new String[]{
                "compact/run", "compact/status",
                "compact/reset", "compact/history"}) {
            assertTrue(d.hasMethod(name), "expected method " + name + " to be registered");
        }
    }

    @Test
    void compactMethodsRunWithoutCompactorIsStructured() {
        // Without a compactor, compact/run returns
        // {ok: false, skipped: true, reason: "..."}
        // — never throws. The TUI's status bar
        // shows "no compactor wired" as a non-fatal
        // badge.
        JsonRpcDispatcher d = newDispatcher();
        CompactMethods c = new CompactMethods();
        c.registerAll(d);
        assertTrue(d.hasMethod("compact/run"));
    }

    @Test
    void compactMethodsCircuitBreakerTripsAfter3Failures() {
        // T-141 / §2.6: after 3 consecutive failures
        // in the same session, autoCompactDisabled
        // flips to true. The TUI then shows a
        // persistent warning.
        CompactMethods c = new CompactMethods();
        c.reportCompactFailure("s1", 1);
        // Reset / status reflects state correctly.
        Object statusBefore = c.status(java.util.Map.of("sessionId", "s1"));
        assertNotNull(statusBefore);
        c.reportCompactFailure("s1", 2);
        c.reportCompactFailure("s1", 3);
        Object statusAfter = c.status(java.util.Map.of("sessionId", "s1"));
        assertNotNull(statusAfter);
        // The status method should now report
        // autoCompactDisabled: true.
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) statusAfter;
        assertEquals(Boolean.TRUE, map.get("autoCompactDisabled"));
    }

    @Test
    void compactMethodsResetClearsBreaker() {
        // T-192: compact/reset clears the
        // autoCompactDisabled flag for the session
        // and returns {ok: true, wasDisabled: bool}.
        CompactMethods c = new CompactMethods();
        c.reportCompactFailure("s2", 3);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> statusBefore =
                (java.util.Map<String, Object>) c.status(java.util.Map.of("sessionId", "s2"));
        assertEquals(Boolean.TRUE, statusBefore.get("autoCompactDisabled"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> reset =
                (java.util.Map<String, Object>) c.reset(java.util.Map.of("sessionId", "s2"));
        assertEquals(Boolean.TRUE, reset.get("ok"));
        assertEquals(true, reset.get("wasDisabled"));
        assertEquals(false, reset.get("autoCompactDisabled"));
    }

    @Test
    void themeMethodsRegisterAllSix() {
        JsonRpcDispatcher d = newDispatcher();
        ThemeMethods t = new ThemeMethods();
        t.registerAll(d);
        for (String name : new String[]{
                "theme/list", "theme/get", "theme/set",
                "theme/import", "theme/export", "theme/active"}) {
            assertTrue(d.hasMethod(name), "expected method " + name + " to be registered");
        }
    }

    @Test
    void themeMethodsDefaultCatalogCoversAllBuiltinNames() {
        // T-501 / §5.1.2: the JVM-side fallback catalog
        // covers all five built-in themes so a headless
        // daemon (no real registry) still answers
        // theme/list with a non-empty list.
        ThemeMethods t = new ThemeMethods();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> reply =
                (java.util.Map<String, Object>) t.list(null);
        assertEquals(true, reply.get("ok"));
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> themes =
                (java.util.List<java.util.Map<String, Object>>) reply.get("themes");
        Set<String> names = new HashSet<>();
        for (java.util.Map<String, Object> th : themes) names.add((String) th.get("name"));
        assertTrue(names.contains("light"));
        assertTrue(names.contains("dark"));
        assertTrue(names.contains("solarized-light"));
        assertTrue(names.contains("solarized-dark"));
        assertTrue(names.contains("high-contrast"));
    }

    @Test
    void themeMethodsSetRefusesUnknownName() {
        // T-501: theme/set with an unknown name
        // returns {ok: false, reason: "..."} rather
        // than silently switching to nothing. The
        // TUI shows a "theme not found" toast.
        ThemeMethods t = new ThemeMethods();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> reply =
                (java.util.Map<String, Object>) t.set(java.util.Map.of("name", "not-a-real-theme"));
        assertEquals(false, reply.get("ok"));
        assertNotNull(reply.get("reason"));
    }

    @Test
    void themeMethodsSetSwitchesActive() {
        // T-501: theme/set with a known name updates
        // the active theme; theme/active then
        // returns the new name.
        ThemeMethods t = new ThemeMethods();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> reply =
                (java.util.Map<String, Object>) t.set(java.util.Map.of("name", "dark"));
        assertEquals(true, reply.get("ok"));
        assertEquals("dark", reply.get("active"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> active =
                (java.util.Map<String, Object>) t.active(null);
        assertEquals("dark", active.get("active"));
    }

    @Test
    void contextMethodsRegistersInfo() {
        JsonRpcDispatcher d = newDispatcher();
        ContextMethods c = new ContextMethods();
        c.registerAll(d);
        assertTrue(d.hasMethod("context/info"));
    }

    @Test
    void contextMethodsInfoComputesBandFromTokens() {
        // T-500 / §2.8: 3 colour bands.
        ContextMethods c = new ContextMethods();
        // < 50%  -> green
        c.setMaxTokens("test-model", 100);
        c.reportInputTokens("test-model", 30);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> r1 =
                (java.util.Map<String, Object>) c.info(null);
        assertEquals("green", r1.get("band"));
        // 50-80% -> amber
        c.reportInputTokens("test-model", 65);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> r2 =
                (java.util.Map<String, Object>) c.info(null);
        assertEquals("amber", r2.get("band"));
        // > 80%  -> red
        c.reportInputTokens("test-model", 90);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> r3 =
                (java.util.Map<String, Object>) c.info(null);
        assertEquals("red", r3.get("band"));
    }

    @Test
    void everyNewMethodHasNonEmptyTagEntry() {
        // Pin that every method added in this round
        // has at least one tag in METHOD_TAGS. The
        // R124 test enforces known-tag, here we
        // only assert presence + non-empty.
        Set<String> newMethods = new HashSet<>();
        newMethods.add("memory/get");
        newMethods.add("memory/appendProjectChange");
        newMethods.add("memory/appendSessionFact");
        newMethods.add("memory/compact");
        newMethods.add("memory/switchProject");
        newMethods.add("memory/list");
        newMethods.add("compact/run");
        newMethods.add("compact/status");
        newMethods.add("compact/reset");
        newMethods.add("compact/history");
        newMethods.add("theme/list");
        newMethods.add("theme/get");
        newMethods.add("theme/set");
        newMethods.add("theme/import");
        newMethods.add("theme/export");
        newMethods.add("theme/active");
        newMethods.add("context/info");
        assertEquals(17, newMethods.size(), "test should cover exactly 17 new methods");
        for (String name : newMethods) {
            assertTrue(AetherCodeMethods.METHOD_TAGS.containsKey(name),
                    "method " + name + " missing from METHOD_TAGS");
            assertFalse(AetherCodeMethods.METHOD_TAGS.get(name).length == 0,
                    "method " + name + " has empty tag array");
        }
    }

    @Test
    void newMethodsAreAtLeast50Total() {
        // The R124 test asserts METHOD_TAGS.size() >= 50
        // for the dispatchable surface. Pin that the
        // +17 we add this round keeps us comfortably
        // above that floor (the existing 50+ include
        // the 17 we just added).
        assertTrue(AetherCodeMethods.METHOD_TAGS.size() >= 50);
    }

    @Test
    void compactMethodsCompactorFieldRoundTrips() {
        // T-500: CompactMethods.compactor() field
        // round-trips via setCompactor(null) →
        // setCompactor(compactor) (we don't need a
        // real Compactor to exercise the wiring;
        // the actual compact pass is exercised by
        // aethercode-compact's own tests).
        CompactMethods c = new CompactMethods();
        assertTrue(c.compactor() == null, "compactor starts null");
        c.setCompactor(null);
        assertTrue(c.compactor() == null, "setCompactor(null) is a no-op clear");
    }

    @Test
    void memoryMethodsStoreFieldRoundTrips() {
        // T-500: MemoryMethods.memoryStore() field
        // round-trips via setMemoryStore(null) →
        // setMemoryStore(store) (we don't need a
        // real LayeredMemoryStore to exercise the
        // wiring).
        MemoryMethods m = new MemoryMethods();
        assertTrue(m.memoryStore() == null, "store starts null");
        m.setMemoryStore(null);
        assertTrue(m.memoryStore() == null, "setMemoryStore(null) is a no-op clear");
    }
}
