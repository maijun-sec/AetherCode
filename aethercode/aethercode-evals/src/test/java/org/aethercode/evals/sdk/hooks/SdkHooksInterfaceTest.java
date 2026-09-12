package org.aethercode.evals.sdk.hooks;

import org.aethercode.hooks.Hook;
import org.aethercode.hooks.Hook.HookContext;
import org.aethercode.hooks.Hook.Outcome;
import org.aethercode.hooks.HookRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-sdk-10: AetherCode Hooks Interface conformance.
 *
 * <p>The {@code aethercode-hooks} module is the engine's safety
 * net. {@code HookRegistry} dispatches to {@code Hook.Kind} in
 * declaration order; the first {@code Block} halts the action;
 * the first {@code ContinueWithResult} wins the mutation.
 * Built-in hooks ({@code WriteExistingFileGuardHook},
 * {@code EditErrorRecoveryHook}, {@code PhaseBudgetHook},
 * {@code PhaseTracker}, {@code TodoContinuationHook}) are wired
 * here.</p>
 *
 * <p>These tests lock the registry's dispatch contract — if any of
 * these rules change, every tool call in production goes through
 * un-vetted hooks.</p>
 */
class SdkHooksInterfaceTest {

    /** Convenience factory: a Hook that returns a fixed outcome
     *  (or computes one from the context). */
    private static Hook stubHook(Hook.Kind kind, Outcome outcome) {
        return new Hook() {
            @Override public Kind kind() { return kind; }
            @Override public java.util.concurrent.CompletableFuture<Outcome> run(HookContext ctx) {
                return java.util.concurrent.CompletableFuture.completedFuture(outcome);
            }
        };
    }

    /** Convenience factory: a Hook whose outcome is computed
     *  from the context (e.g. increments a counter). */
    private static Hook stubHook(Hook.Kind kind,
                                  java.util.function.Function<HookContext, Outcome> body) {
        return new Hook() {
            @Override public Kind kind() { return kind; }
            @Override public java.util.concurrent.CompletableFuture<Outcome> run(HookContext ctx) {
                return java.util.concurrent.CompletableFuture.completedFuture(body.apply(ctx));
            }
        };
    }

    /* ---------------- Hook.Kind taxonomy ---------------- */

    @Test
    void hookKindTaxonomyIsStable() {
        // The kinds are the API: a hook advertises what it cares
        // about. Adding a kind is OK; removing / renaming one
        // breaks every registered hook of that kind.
        Hook.Kind[] expected = {
                Hook.Kind.PRE_TOOL_USE,
                Hook.Kind.POST_TOOL_USE,
                Hook.Kind.USER_PROMPT_SUBMIT,
                Hook.Kind.STOP,
                Hook.Kind.SESSION_IDLE,
                Hook.Kind.PRE_MODEL_QUERY,
                Hook.Kind.POST_STREAM_END
        };
        assertEquals(expected.length, Hook.Kind.values().length,
                "Hook.Kind taxonomy drifted — review hook subscribers");
    }

    /* ---------------- HookRegistry: register / snapshot ---------------- */

    @Test
    void hookRegistryRegisterAddsHook() {
        HookRegistry r = new HookRegistry();
        Hook h = stubHook(Hook.Kind.PRE_TOOL_USE, new Outcome.Continue());
        r.register(h);
        assertEquals(1, r.snapshot().size());
        assertEquals(h, r.snapshot().get(0));
    }

    @Test
    void hookRegistrySnapshotIsUnmodifiable() {
        HookRegistry r = new HookRegistry();
        r.register(stubHook(Hook.Kind.PRE_TOOL_USE, new Outcome.Continue()));
        List<Hook> snap = r.snapshot();
        // Defensive copy: mutating the returned list must not
        // affect the registry.
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> snap.add(stubHook(Hook.Kind.STOP, new Outcome.Continue())));
    }

    @Test
    void hookRegistryRegisterIfAbsentDeduplicates() {
        HookRegistry r = new HookRegistry();
        Hook a = stubHook(Hook.Kind.PRE_TOOL_USE, new Outcome.Continue());
        Hook b = stubHook(Hook.Kind.PRE_TOOL_USE, new Outcome.Continue());
        // Same class → second registerIfAbsent returns false.
        assertTrue(r.registerIfAbsent(a));
        assertFalse(r.registerIfAbsent(b));
        assertEquals(1, r.snapshot().size());
    }

    @Test
    void hookRegistryRegisterIfAbsentRejectsNull() {
        HookRegistry r = new HookRegistry();
        assertFalse(r.registerIfAbsent(null));
    }

    /* ---------------- runAll: dispatch + first-Block-wins ---------------- */

    @Test
    void runAllFiresHooksMatchingKindOnly() throws Exception {
        HookRegistry r = new HookRegistry();
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        r.register(stubHook(Hook.Kind.PRE_TOOL_USE, ctx -> {
            preCalls.incrementAndGet();
            return new Outcome.Continue();
        }));
        r.register(stubHook(Hook.Kind.POST_TOOL_USE, ctx -> {
            postCalls.incrementAndGet();
            return new Outcome.Continue();
        }));
        Outcome out = r.runAll(Hook.Kind.PRE_TOOL_USE, null).get(5, TimeUnit.SECONDS);
        assertInstanceOf(Outcome.Continue.class, out);
        assertEquals(1, preCalls.get());
        assertEquals(0, postCalls.get(), "POST hook didn't match PRE kind");
    }

    @Test
    void runAllShortCircuitsOnBlock() throws Exception {
        HookRegistry r = new HookRegistry();
        AtomicInteger firstCalled = new AtomicInteger();
        AtomicInteger secondCalled = new AtomicInteger();
        r.register(stubHook(Hook.Kind.PRE_TOOL_USE, ctx -> {
            firstCalled.incrementAndGet();
            return new Outcome.Block("stop");
        }));
        r.register(stubHook(Hook.Kind.PRE_TOOL_USE, ctx -> {
            secondCalled.incrementAndGet();
            return new Outcome.Continue();
        }));
        Outcome out = r.runAll(Hook.Kind.PRE_TOOL_USE, null).get(5, TimeUnit.SECONDS);
        assertInstanceOf(Outcome.Block.class, out);
        assertEquals(1, firstCalled.get());
        assertEquals(0, secondCalled.get(), "first Block should short-circuit");
    }

    @Test
    void runAllFirstMutationWins() throws Exception {
        HookRegistry r = new HookRegistry();
        r.register(stubHook(Hook.Kind.POST_TOOL_USE, ctx ->
                Outcome.ContinueWithResult.replaceOutput("first")));
        r.register(stubHook(Hook.Kind.POST_TOOL_USE, ctx ->
                Outcome.ContinueWithResult.replaceOutput("second")));
        Outcome out = r.runAll(Hook.Kind.POST_TOOL_USE, null).get(5, TimeUnit.SECONDS);
        assertInstanceOf(Outcome.ContinueWithResult.class, out);
        Outcome.ContinueWithResult cw = (Outcome.ContinueWithResult) out;
        assertEquals("first", cw.newOutput(),
                "first ContinueWithResult wins, second is ignored");
    }

    @Test
    void runAllReturnsContinueWhenNoMatchingHooks() throws Exception {
        HookRegistry r = new HookRegistry();
        Outcome out = r.runAll(Hook.Kind.STOP, null).get(5, TimeUnit.SECONDS);
        assertInstanceOf(Outcome.Continue.class, out);
    }

    @Test
    void runAllExecutesInRegistrationOrder() throws Exception {
        HookRegistry r = new HookRegistry();
        java.util.List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        r.register(stubHook(Hook.Kind.PRE_TOOL_USE, ctx -> {
            order.add("first");
            return new Outcome.Continue();
        }));
        r.register(stubHook(Hook.Kind.PRE_TOOL_USE, ctx -> {
            order.add("second");
            return new Outcome.Continue();
        }));
        r.register(stubHook(Hook.Kind.PRE_TOOL_USE, ctx -> {
            order.add("third");
            return new Outcome.Continue();
        }));
        r.runAll(Hook.Kind.PRE_TOOL_USE, null).get(5, TimeUnit.SECONDS);
        assertEquals(List.of("first", "second", "third"), order);
    }

    /* ---------------- HookContext smoke ---------------- */

    /* ---------------- HookContext smoke ---------------- */
    // HookContext has 10 constructor parameters; we don't try to
    // construct it in this round (the engine builds it from the
    // chat client) but the registry accepts null contexts fine,
    // which is the contract the rest of the system depends on.

    /* ---------------- Outcome factories ---------------- */

    @Test
    void outcomeContinueFactoryReturnsContinue() {
        assertInstanceOf(Outcome.Continue.class, new Outcome.Continue());
    }

    @Test
    void outcomeBlockCarriesReason() {
        Outcome.Block b = new Outcome.Block("tool not allowed");
        assertEquals("tool not allowed", b.reason());
    }

    @Test
    void outcomeContinueWithResultReplaceOutputFactory() {
        Outcome.ContinueWithResult cw = Outcome.ContinueWithResult.replaceOutput("rewritten");
        assertEquals("rewritten", cw.newOutput());
    }

    @Test
    void outcomeContinueWithResultMarkSuccess() {
        Outcome.ContinueWithResult cw = Outcome.ContinueWithResult.markSuccess();
        // markSuccess means "we replaced nothing but mark the
        // tool result as success" — the newOutput may be null
        // or empty depending on the implementation; what matters
        // is that the result is recognised as a ContinueWithResult.
        assertInstanceOf(Outcome.ContinueWithResult.class, cw);
    }
}
