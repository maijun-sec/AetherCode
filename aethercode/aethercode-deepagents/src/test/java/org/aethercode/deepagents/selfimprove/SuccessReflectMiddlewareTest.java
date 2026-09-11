package org.aethercode.deepagents.selfimprove;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.deepagents.tools.Tool;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuccessReflectMiddlewareTest {

    private static Tool successTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "always succeeds"; }
            @Override public Map<String, Object> argsSchema() { return Map.of(); }
            @Override public Object invoke(Map<String, Object> a) { return "ok-" + name; }
            @Override public Tool withDescription(String s) { return this; }
        };
    }

    private static Tool failingTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "always fails"; }
            @Override public Map<String, Object> argsSchema() { return Map.of(); }
            @Override public Object invoke(Map<String, Object> a) {
                throw new RuntimeException("boom");
            }
            @Override public Tool withDescription(String s) { return this; }
        };
    }

    @Test
    void successfulToolCallStoresReflection() throws Exception {
        StubReflector reflector = new StubReflector();
        reflector.registerDefault("fix_strategy: chose the right tool\nexample:");
        ReasoningBank bank = new ReasoningBank();
        SuccessReflectMiddleware mw = new SuccessReflectMiddleware(reflector, bank);

        Object result = mw.wrapToolCall(successTool("read_file"), Map.of("path", "/tmp/x"),
                AgentState.empty(), null);
        assertEquals("ok-read_file", result);
        assertEquals(1, bank.size());
        assertEquals(1, mw.totalReflections());
        assertEquals("tool_success", bank.kinds().iterator().next());
    }

    @Test
    void failingToolCallIsNotReflectedAsSuccess() {
        StubReflector reflector = new StubReflector();
        reflector.registerDefault("fix_strategy: should not be stored");
        ReasoningBank bank = new ReasoningBank();
        SuccessReflectMiddleware mw = new SuccessReflectMiddleware(reflector, bank);

        try {
            mw.wrapToolCall(failingTool("read_file"), Map.of(), AgentState.empty(), null);
        } catch (Exception expected) {
            // expected
        }
        // Failure path is owned by SelfReflectMiddleware; success
        // path does not store anything on exceptions.
        assertEquals(0, bank.size());
        assertEquals(0, mw.totalReflections());
    }

    @Test
    void maxSuccessesZeroDisablesReflection() throws Exception {
        StubReflector reflector = new StubReflector();
        reflector.registerDefault("fix_strategy: any");
        ReasoningBank bank = new ReasoningBank();
        SuccessReflectMiddleware mw = new SuccessReflectMiddleware(
                reflector, bank, SuccessClassifier.always(), null, 0);

        for (int i = 0; i < 5; i++) {
            mw.wrapToolCall(successTool("t" + i), Map.of(), AgentState.empty(), null);
        }
        assertEquals(0, bank.size());
        assertEquals(0, mw.totalReflections());
        assertEquals(5, mw.skippedDisabled());
    }

    @Test
    void duplicateSuccessesAreDeduped() throws Exception {
        StubReflector reflector = new StubReflector();
        reflector.registerDefault("fix_strategy: same");
        ReasoningBank bank = new ReasoningBank();
        SuccessReflectMiddleware mw = new SuccessReflectMiddleware(
                reflector, bank, SuccessClassifier.always(), null, 100);

        for (int i = 0; i < 3; i++) {
            mw.wrapToolCall(successTool("t1"), Map.of(), AgentState.empty(), null);
        }
        assertEquals(1, bank.size());
        assertEquals(1, mw.totalReflections());
        assertEquals(2, mw.skippedDedup());
    }

    @Test
    void differentToolsEachStoreOneReflection() throws Exception {
        StubReflector reflector = new StubReflector();
        reflector.registerDefault("fix_strategy: ok");
        ReasoningBank bank = new ReasoningBank();
        SuccessReflectMiddleware mw = new SuccessReflectMiddleware(
                reflector, bank, SuccessClassifier.always(), null, 100);

        mw.wrapToolCall(successTool("a"), Map.of(), AgentState.empty(), null);
        mw.wrapToolCall(successTool("b"), Map.of(), AgentState.empty(), null);
        mw.wrapToolCall(successTool("c"), Map.of(), AgentState.empty(), null);
        assertEquals(3, bank.size());
        assertEquals(3, mw.totalReflections());
    }

    @Test
    void emptyReflectorResponseIsSkipped() throws Exception {
        StubReflector reflector = new StubReflector();
        // registerDefault not called → empty default
        ReasoningBank bank = new ReasoningBank();
        SuccessReflectMiddleware mw = new SuccessReflectMiddleware(reflector, bank);

        mw.wrapToolCall(successTool("t"), Map.of(), AgentState.empty(), null);
        assertEquals(0, bank.size());
        assertEquals(0, mw.totalReflections());
        assertEquals(1, mw.skippedReflectorError());
    }

    @Test
    void reflectorExceptionIsSwallowed() throws Exception {
        Reflector broken = (systemPrompt, userPrompt) -> { throw new RuntimeException("nope"); };
        ReasoningBank bank = new ReasoningBank();
        SuccessReflectMiddleware mw = new SuccessReflectMiddleware(broken, bank);

        Object result = mw.wrapToolCall(successTool("t"), Map.of(), AgentState.empty(), null);
        assertNotNull(result);
        assertEquals(0, bank.size());
        assertEquals(1, mw.skippedReflectorError());
    }

    @Test
    void neverClassifierStoresNothing() throws Exception {
        StubReflector reflector = new StubReflector();
        reflector.registerDefault("fix_strategy: should be ignored");
        ReasoningBank bank = new ReasoningBank();
        SuccessReflectMiddleware mw = new SuccessReflectMiddleware(
                reflector, bank, SuccessClassifier.never(), null, 1);

        mw.wrapToolCall(successTool("t"), Map.of(), AgentState.empty(), null);
        assertEquals(0, bank.size());
        assertEquals(0, mw.totalReflections());
    }

    @Test
    void recentSuccessesBoundedByMax() throws Exception {
        StubReflector reflector = new StubReflector();
        reflector.registerDefault("fix_strategy: ok");
        ReasoningBank bank = new ReasoningBank();

        // Use a unique-description classifier so every call
        // passes dedup and lands in the recentSuccesses buffer.
        AtomicInteger counter = new AtomicInteger();
        SuccessClassifier unique = (tool, args, result) -> {
            int n = counter.incrementAndGet();
            return new SuccessClassifier.Classification("u" + n, "tool_success");
        };
        SuccessReflectMiddleware mw = new SuccessReflectMiddleware(
                reflector, bank, unique, null, 100);
        for (int i = 0; i < 20; i++) {
            mw.wrapToolCall(successTool("t"), Map.of(), AgentState.empty(), null);
        }
        assertEquals(20, bank.size());
        assertEquals(8, mw.recentSuccesses().size());
    }

    @Test
    void successPromptMentionsStrategyShape() {
        // Lock in the prompt-shape contract so a stray
        // refactor cannot silently break the parser.
        String p = SuccessReflectPrompts.DEFAULT_SYSTEM_PROMPT;
        assertTrue(p.contains("fix_strategy"),
                "system prompt should ask for fix_strategy");
        assertTrue(p.contains("error_pattern"),
                "system prompt should ask for error_pattern (n/a ok)");
    }
}
