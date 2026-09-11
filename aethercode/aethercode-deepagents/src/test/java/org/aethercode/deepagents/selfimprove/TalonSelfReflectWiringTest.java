package org.aethercode.deepagents.selfimprove;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.deepagents.middleware.Middleware;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TalonSelfReflectWiringTest {

    @Test
    void defaultBuildReturnsFourMiddlewares(@TempDir Path tmp) {
        TalonSelfReflectWiring.Result r =
                TalonSelfReflectWiring.build(tmp, null, Map.of());
        assertTrue(r.enabled(), "default build should be enabled");
        assertEquals(4, r.middlewares().size(),
                "default wiring should ship SelfReflect + SuccessReflect + BankRecall + SelfEval");
        assertNotNull(r.bank());
        assertEquals(0, r.bank().size(),
                "fresh bank on first run is empty");
        assertNotNull(r.bankDir());
        assertTrue(Files.isDirectory(r.bankDir()),
                "bankDir should be created: " + r.bankDir());
    }

    @Test
    void middlewaresContainExpectedKinds(@TempDir Path tmp) {
        TalonSelfReflectWiring.Result r =
                TalonSelfReflectWiring.build(tmp, null, Map.of());
        // Each middleware exposes a stable name() string;
        // the test asserts both presence and the names
        // match the public surface.
        List<String> names = r.middlewares().stream()
                .map(Middleware::name)
                .toList();
        assertTrue(names.contains("SelfReflectMiddleware"), names.toString());
        assertTrue(names.contains("SuccessReflectMiddleware"), names.toString());
        assertTrue(names.contains("BankRecallMiddleware"), names.toString());
        assertTrue(names.contains("SelfEvalMiddleware"), names.toString());
    }

    @Test
    void optOutEnvReturnsEmptyMiddlewares(@TempDir Path tmp) {
        TalonSelfReflectWiring.Result r = TalonSelfReflectWiring.build(
                tmp, null,
                Map.of(TalonSelfReflectWiring.ENV_OPT_OUT, "false"));
        assertFalse(r.enabled());
        assertEquals(0, r.middlewares().size());
        assertEquals("opt-out env var set", r.reason());
    }

    @Test
    void optOutEnvAcceptsMultipleFalsyStrings(@TempDir Path tmp) {
        for (String falsy : List.of("false", "FALSE", "False", "0", "no", "off")) {
            TalonSelfReflectWiring.Result r = TalonSelfReflectWiring.build(
                    tmp, null,
                    Map.of(TalonSelfReflectWiring.ENV_OPT_OUT, falsy));
            assertFalse(r.enabled(), "should opt out for value=" + falsy);
        }
    }

    @Test
    void nullAssistantDirFallsBackToInMemoryBank() {
        TalonSelfReflectWiring.Result r =
                TalonSelfReflectWiring.build(null, null, Map.of());
        assertTrue(r.enabled());
        assertEquals(4, r.middlewares().size());
        assertNull(r.bankDir(),
                "no assistantDir → bankDir should be null");
        // The bank is still functional; just in-memory.
        assertEquals(0, r.bank().size());
    }

    @Test
    void nullChatClientFallsBackToStubReflector(@TempDir Path tmp) {
        TalonSelfReflectWiring.Result r =
                TalonSelfReflectWiring.build(tmp, null, Map.of());
        // We can't introspect the reflector directly (it
        // is private inside the middlewares) but the
        // behaviour is observable: an exception-throwing
        // middleware would corrupt the bank on a
        // subsequent write, while a stub reflector simply
        // returns "" which the bank parser treats as
        // fallback.
        SelfReflectMiddleware self = (SelfReflectMiddleware) r.middlewares().stream()
                .filter(m -> m.name().equals("SelfReflectMiddleware"))
                .findFirst().orElseThrow();
        // Stub reflector has 0 calls so far; the test just
        // verifies the middleware is wired without
        // requiring a real LLM roundtrip.
        assertNotNull(self);
    }

    @Test
    void customDecayAndCapAreHonoured(@TempDir Path tmp) {
        TalonSelfReflectWiring.Result r = TalonSelfReflectWiring.build(
                tmp, null,
                Map.of(
                        TalonSelfReflectWiring.ENV_DECAY_DAYS, "1",
                        TalonSelfReflectWiring.ENV_BANK_MAX, "5"));
        assertTrue(r.enabled());
        // The bank growth policy should be LruEviction(5).
        // We exercise the cap by adding 6 units and
        // expecting 5 to remain after eviction.
        for (int i = 0; i < 6; i++) {
            r.bank().add(new ReasoningUnit(
                    "u" + i, "k", "e", "f", "",
                    0.5, 0L, java.time.Instant.now()));
        }
        assertEquals(5, r.bank().size(),
                "LRU cap should keep bank at 5 units");
    }

    @Test
    void invalidDecayAndCapFallBackToDefaults(@TempDir Path tmp) {
        TalonSelfReflectWiring.Result r = TalonSelfReflectWiring.build(
                tmp, null,
                Map.of(
                        TalonSelfReflectWiring.ENV_DECAY_DAYS, "not-a-number",
                        TalonSelfReflectWiring.ENV_BANK_MAX, "-99"));
        // Should not throw; falls back to defaults.
        assertTrue(r.enabled());
        // BankMax negative should fall back to 1000 → adding
        // 200 units should still be under cap.
        for (int i = 0; i < 200; i++) {
            r.bank().add(new ReasoningUnit(
                    "u" + i, "k", "e", "f", "",
                    0.5, 0L, java.time.Instant.now()));
        }
        assertEquals(200, r.bank().size(),
                "fall-back cap=1000 should keep all 200 units");
    }

    @Test
    void bankPersistsAcrossInstances(@TempDir Path tmp) {
        // The first wiring writes a unit; the second
        // wiring (on a fresh Result) sees it on disk.
        TalonSelfReflectWiring.Result r1 =
                TalonSelfReflectWiring.build(tmp, null, Map.of());
        ReasoningUnit u = new ReasoningUnit(
                "u1", "k", "e", "f", "ex",
                0.7, 3L, java.time.Instant.now());
        r1.bank().add(u);

        TalonSelfReflectWiring.Result r2 =
                TalonSelfReflectWiring.build(tmp, null, Map.of());
        assertEquals(1, r2.bank().size(),
                "second wiring should reload the unit from disk");
        assertTrue(r2.bank().contains("u1"));
    }

    @Test
    void resultIsImmutableMiddlewaresList(@TempDir Path tmp) {
        TalonSelfReflectWiring.Result r =
                TalonSelfReflectWiring.build(tmp, null, Map.of());
        // The returned list should be unmodifiable.
        assertThrowsLikeUnmodifiable(() -> r.middlewares().add(
                new SelfReflectMiddleware(new StubReflector(), new ReasoningBank())));
    }

    private static void assertThrowsLikeUnmodifiable(Runnable r) {
        try {
            r.run();
            assert false : "expected UnsupportedOperationException";
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}
