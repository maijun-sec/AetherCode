package org.aethercode.deepagents.selfimprove;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.deepagents.tools.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R241.2 (O-3): tests for the
 * {@link SelfReflectMiddleware} — failure detection,
 * reflector dispatch, dedup, and bank population.
 */
class SelfReflectMiddlewareTest {

    private ReasoningBank bank;
    private StubReflector reflector;
    private SelfReflectMiddleware mw;

    @BeforeEach
    void setUp() {
        bank = new ReasoningBank();
        reflector = new StubReflector();
        mw = new SelfReflectMiddleware(reflector, bank);
    }

    /** Minimal tool that throws. */
    private static final Tool FAILING_TOOL = new Tool() {
        @Override public String name() { return "failing_tool"; }
        @Override public String description() { return "always throws"; }
        @Override public java.util.Map<String, Object> argsSchema() { return Map.of(); }
        @Override public Object invoke(java.util.Map<String, Object> arguments) {
            throw new RuntimeException("simulated failure: bad path");
        }
        @Override public Tool withDescription(String s) { return this; }
    };

    /** Minimal tool that succeeds. */
    private static final Tool OK_TOOL = new Tool() {
        @Override public String name() { return "ok_tool"; }
        @Override public String description() { return "always ok"; }
        @Override public java.util.Map<String, Object> argsSchema() { return Map.of(); }
        @Override public Object invoke(java.util.Map<String, Object> arguments) {
            return "ok";
        }
        @Override public Tool withDescription(String s) { return this; }
    };

    @Test
    void wrapToolCallRethrowsAndStoresReflection() {
        AgentState state = AgentState.empty();
        reflector.registerDefault("error_pattern: x\nfix_strategy: y\nexample: z");
        Throwable caught = assertThrows(RuntimeException.class,
                () -> mw.wrapToolCall(FAILING_TOOL, Map.of("path", "/bad"), state, null));
        assertEquals("simulated failure: bad path", caught.getMessage());
        assertEquals(1, bank.size());
        assertEquals(1, bank.sizeForKind("tool_error"));
        assertEquals(1L, mw.totalReflections());
        assertEquals(1, reflector.totalCalls());
    }

    @Test
    void wrapToolCallSucceedsWithoutReflection() throws Exception {
        AgentState state = AgentState.empty();
        Object out = mw.wrapToolCall(OK_TOOL, Map.of(), state, null);
        assertEquals("ok", out);
        assertEquals(0, bank.size());
        assertEquals(0L, mw.totalReflections());
    }

    @Test
    void duplicateFailuresAreDeduped() {
        AgentState state = AgentState.empty();
        reflector.registerDefault("error_pattern: x\nfix_strategy: y");
        // First call: stores one unit
        assertThrows(RuntimeException.class,
                () -> mw.wrapToolCall(FAILING_TOOL, Map.of(), state, null));
        // Second call: same exception → dedup
        assertThrows(RuntimeException.class,
                () -> mw.wrapToolCall(FAILING_TOOL, Map.of(), state, null));
        assertEquals(1, bank.size());
        assertEquals(1L, mw.totalReflections());
        assertEquals(1L, mw.skippedDedup());
    }

    @Test
    void differentFailuresBothStored() {
        AgentState state = AgentState.empty();
        reflector.registerDefault("error_pattern: x\nfix_strategy: y");
        // Use an unlimited middleware so this test isolates
        // the "two distinct failure descriptions both get a
        // unit" property from the per-turn cap (covered by
        // maxReflectionsPerTurnCapsStoreRate below).
        SelfReflectMiddleware unlimited = new SelfReflectMiddleware(
                reflector, bank, FailureClassifier.always(), null, 0);
        // First failure
        assertThrows(RuntimeException.class,
                () -> unlimited.wrapToolCall(new Tool() {
                    @Override public String name() { return "t1"; }
                    @Override public String description() { return ""; }
                    @Override public java.util.Map<String, Object> argsSchema() { return Map.of(); }
                    @Override public Object invoke(java.util.Map<String, Object> a) {
                        throw new RuntimeException("first failure");
                    }
                    @Override public Tool withDescription(String s) { return this; }
                }, Map.of(), state, null));
        // Second failure with different message
        assertThrows(RuntimeException.class,
                () -> unlimited.wrapToolCall(new Tool() {
                    @Override public String name() { return "t2"; }
                    @Override public String description() { return ""; }
                    @Override public java.util.Map<String, Object> argsSchema() { return Map.of(); }
                    @Override public Object invoke(java.util.Map<String, Object> a) {
                        throw new IllegalStateException("second failure");
                    }
                    @Override public Tool withDescription(String s) { return this; }
                }, Map.of(), state, null));
        assertEquals(2, bank.size());
        assertEquals(2L, unlimited.totalReflections());
    }

    @Test
    void maxReflectionsPerTurnCapsStoreRate() {
        // Build a middleware that allows only 1 reflection per turn.
        SelfReflectMiddleware capped = new SelfReflectMiddleware(
                reflector, bank, FailureClassifier.always(),
                SelfReflectPrompts.DEFAULT_SYSTEM_PROMPT, 1);
        reflector.registerDefault("error_pattern: x\nfix_strategy: y");
        // First call stores
        assertThrows(RuntimeException.class,
                () -> capped.wrapToolCall(FAILING_TOOL, Map.of(), AgentState.empty(), null));
        // Second call (different exception) is capped, not stored
        assertThrows(RuntimeException.class,
                () -> capped.wrapToolCall(new Tool() {
                    @Override public String name() { return "t"; }
                    @Override public String description() { return ""; }
                    @Override public java.util.Map<String, Object> argsSchema() { return Map.of(); }
                    @Override public Object invoke(java.util.Map<String, Object> a) {
                        throw new IllegalArgumentException("different");
                    }
                    @Override public Tool withDescription(String s) { return this; }
                }, Map.of(), AgentState.empty(), null));
        assertEquals(1, bank.size());
    }

    @Test
    void reflectorExceptionIsSwallowed() {
        AgentState state = AgentState.empty();
        // StubReflector returns empty by default; "empty" is
        // treated as a failure to record. To exercise the
        // exception path, install a throwing reflector:
        Reflector broken = (sys, user) -> { throw new RuntimeException("net down"); };
        SelfReflectMiddleware m = new SelfReflectMiddleware(broken, bank);
        assertThrows(RuntimeException.class,
                () -> m.wrapToolCall(FAILING_TOOL, Map.of(), state, null));
        assertEquals(0, bank.size());
        assertEquals(1L, m.skippedReflectorError());
    }

    @Test
    void emptyReflectorResponseIsSkipped() {
        AgentState state = AgentState.empty();
        // StubReflector with no default returns ""; this
        // should be treated as a no-op (don't store garbage).
        assertThrows(RuntimeException.class,
                () -> mw.wrapToolCall(FAILING_TOOL, Map.of(), state, null));
        assertEquals(0, bank.size());
        assertEquals(1L, mw.skippedReflectorError());
    }

    @Test
    void customClassifierChangesTaskKind() {
        AgentState state = AgentState.empty();
        FailureClassifier buildOnly = (toolName, args, error) ->
                new FailureClassifier.Classification("build",
                        "build failed for " + toolName);
        SelfReflectMiddleware m = new SelfReflectMiddleware(reflector, bank, buildOnly, null, 5);
        reflector.registerDefault("error_pattern: build broke\nfix_strategy: re-run");
        assertThrows(RuntimeException.class,
                () -> m.wrapToolCall(FAILING_TOOL, Map.of(), state, null));
        assertEquals(1, bank.sizeForKind("build"));
        assertEquals(0, bank.sizeForKind("tool_error"));
    }

    @Test
    void recentFailuresListBoundedByMax() {
        // Each distinct exception message gets a slot; verify
        // that the ring buffer caps at MAX_RECENT. We use an
        // unlimited middleware here so the per-turn cap does
        // not short-circuit the test, and we read the buffer
        // through the public accessor because the ring is
        // intentionally kept in-memory (AgentState is
        // immutable and wrapToolCall has no way to return a
        // new state).
        AgentState state = AgentState.empty();
        reflector.registerDefault("error_pattern: x\nfix_strategy: y");
        SelfReflectMiddleware unlimited = new SelfReflectMiddleware(
                reflector, bank, FailureClassifier.always(), null, 0);
        for (int i = 0; i < SelfReflectMiddleware.MAX_RECENT + 5; i++) {
            final int idx = i;
            assertThrows(RuntimeException.class,
                    () -> unlimited.wrapToolCall(new Tool() {
                        @Override public String name() { return "t" + idx; }
                        @Override public String description() { return ""; }
                        @Override public java.util.Map<String, Object> argsSchema() { return Map.of(); }
                        @Override public Object invoke(java.util.Map<String, Object> a) {
                            throw new RuntimeException("err-" + idx);
                        }
                        @Override public Tool withDescription(String s) { return this; }
                    }, Map.of(), state, null));
        }
        List<String> recent = unlimited.recentFailures();
        assertNotNull(recent);
        assertEquals(SelfReflectMiddleware.MAX_RECENT, recent.size());
        // The most-recent description must mention the most-recent
        // exception message — the ring buffer evicts from the
        // head, so the tail is the latest entry.
        String last = recent.get(recent.size() - 1);
        assertTrue(last.contains("err-12"),
                "last entry should mention err-12, was: " + last);
    }

    @Test
    void defaultSystemPromptMentionsKeyValueShape() {
        // The default prompt should instruct the model on the
        // exact output format the parser expects.
        String p = SelfReflectPrompts.DEFAULT_SYSTEM_PROMPT;
        assertTrue(p.contains("error_pattern"));
        assertTrue(p.contains("fix_strategy"));
    }

    @Test
    void counterStartsAtZero() {
        assertEquals(0L, mw.totalReflections());
        assertEquals(0L, mw.skippedDedup());
        assertEquals(0L, mw.skippedReflectorError());
    }
}
