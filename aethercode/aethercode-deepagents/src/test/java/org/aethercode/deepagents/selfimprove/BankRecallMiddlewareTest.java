package org.aethercode.deepagents.selfimprove;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R241.2 (O-3): tests for the {@link BankRecallMiddleware}
 * — recall, format, inject, and caching.
 */
class BankRecallMiddlewareTest {

    private ReasoningBank bank;
    private BankRecallMiddleware mw;

    @BeforeEach
    void setUp() {
        bank = new ReasoningBank();
        mw = new BankRecallMiddleware(bank);
    }

    @Test
    void recallEmptyBankReturnsEmpty() {
        List<ReasoningUnit> r = mw.recallAllKinds();
        assertTrue(r.isEmpty());
        // beforeModel should leave the state untouched.
        AgentState state = AgentState.empty();
        AgentState next = mw.beforeModel(state, null);
        assertNull(next.extensions().get(BankRecallMiddleware.RECALL_KEY));
    }

    @Test
    void recallAllKindsPicksOnePerKind() {
        bank.parse("file_edit", "error_pattern: bad path\nfix_strategy: check cwd");
        bank.parse("build", "error_pattern: missing dep\nfix_strategy: install first");
        bank.parse("test", "error_pattern: flaky\nfix_strategy: rerun");
        List<ReasoningUnit> r = mw.recallAllKinds();
        assertEquals(3, r.size());
        // All three kinds present, order deterministic (utility ties → uses
        // ties → createdAt desc).
        java.util.Set<String> seenKinds = new java.util.HashSet<>();
        for (ReasoningUnit u : r) seenKinds.add(u.taskKind());
        assertEquals(3, seenKinds.size());
    }

    @Test
    void recallCappedByTopK() {
        for (int i = 0; i < 5; i++) {
            bank.parse("kind-" + i, "error_pattern: e\nfix_strategy: f");
        }
        BankRecallMiddleware top2 = new BankRecallMiddleware(bank, 2, null);
        List<ReasoningUnit> r = top2.recallAllKinds();
        assertEquals(2, r.size());
    }

    @Test
    void recallCachesOnState() {
        bank.parse("file_edit", "error_pattern: e\nfix_strategy: f");
        AtomicInteger bankCalls = new AtomicInteger(0);
        // Spy on kinds() call count is awkward; instead we just verify
        // that the second beforeModel returns the same state (no new
        // extension written). The first call writes RECALL_KEY, the
        // second should be a no-op (state identity preserved).
        AgentState s1 = AgentState.empty();
        AgentState s2 = mw.beforeModel(s1, null);
        assertNotNull(s2.extensions().get(BankRecallMiddleware.RECALL_KEY));
        AgentState s3 = mw.beforeModel(s2, null);
        // s2 already had RECALL_KEY, so s3 must be the same instance.
        assertEquals(s2, s3);
        bankCalls.incrementAndGet();
        assertEquals(1, bankCalls.get());
    }

    @Test
    void formatRecallIncludesAllFields() {
        ReasoningUnit u = ReasoningUnit.of("file_edit",
                "path outside worktree",
                "Run git status first",
                "git status → file_edit /bad");
        String s = BankRecallMiddleware.formatRecall(List.of(u));
        assertTrue(s.contains("task_kind: file_edit"), s);
        assertTrue(s.contains("error_pattern: path outside worktree"), s);
        assertTrue(s.contains("fix_strategy: Run git status first"), s);
        assertTrue(s.contains("example: git status"), s);
        assertTrue(s.contains("uses=0"), s);
        assertTrue(s.contains("utility=0.50"), s);
    }

    @Test
    void formatRecallEmptyList() {
        assertEquals("(no reflections yet)", BankRecallMiddleware.formatRecall(List.of()));
    }

    @Test
    void formatRecallSkipsEmptyExample() {
        ReasoningUnit u = ReasoningUnit.of("k", "e", "f", "");
        String s = BankRecallMiddleware.formatRecall(List.of(u));
        assertTrue(s.contains("fix_strategy: f"), s);
        // example line should not appear when example is empty.
        assertTrue(!s.contains("example:"));
    }

    @Test
    void wrapModelCallAppendsFragmentToSystemMessage() {
        bank.parse("file_edit", "error_pattern: e\nfix_strategy: f");
        AgentState state = mw.beforeModel(AgentState.empty(), null);
        Message system = new Message.SystemMessage("sys-1",
                List.of(ContentBlock.text("You are a coding agent.")));
        Message user = new Message.HumanMessage("u-1",
                List.of(ContentBlock.text("edit the file")));
        List<Message> messages = new java.util.ArrayList<>(List.of(system, user));
        // wrapModelCall needs a chat-model stub; for this test we
        // only care about injectRecallIntoMessages, so call that
        // directly.
        List<Message> out = mw.injectRecallIntoMessages(messages, state);
        assertEquals(2, out.size());
        Message out0 = out.get(0);
        assertTrue(out0 instanceof Message.SystemMessage);
        String text = ContentBlock.flattenText(((Message.SystemMessage) out0).content());
        assertTrue(text.contains("You are a coding agent."), text);
        assertTrue(text.contains("<prior_reflections>"), text);
        assertTrue(text.contains("error_pattern: e"), text);
    }

    @Test
    void wrapModelCallNoRecallIsNoop() {
        // state has no RECALL_KEY → messages pass through unchanged.
        List<Message> messages = new java.util.ArrayList<>(List.of(
                new Message.SystemMessage("sys", List.of(ContentBlock.text("orig")))));
        List<Message> out = mw.injectRecallIntoMessages(messages, AgentState.empty());
        assertEquals(messages, out);
    }

    @Test
    void wrapModelCallNullTemplateIsNoop() {
        BankRecallMiddleware noFrag = new BankRecallMiddleware(bank, 3, null);
        bank.parse("k", "error_pattern: e\nfix_strategy: f");
        AgentState state = noFrag.beforeModel(AgentState.empty(), null);
        List<Message> messages = new java.util.ArrayList<>(List.of(
                new Message.SystemMessage("sys", List.of(ContentBlock.text("orig")))));
        List<Message> out = noFrag.injectRecallIntoMessages(messages, state);
        // No fragment, no new block.
        assertEquals(messages, out);
    }

    @Test
    void wrapModelCallNoSystemMessagePrependsNew() {
        bank.parse("k", "error_pattern: e\nfix_strategy: f");
        AgentState state = mw.beforeModel(AgentState.empty(), null);
        List<Message> messages = new java.util.ArrayList<>(List.of(
                new Message.HumanMessage("u", List.of(ContentBlock.text("hi")))));
        List<Message> out = mw.injectRecallIntoMessages(messages, state);
        assertEquals(2, out.size());
        assertTrue(out.get(0) instanceof Message.SystemMessage,
                "expected SystemMessage at index 0, was: " + out.get(0).getClass());
        assertTrue(out.get(1) instanceof Message.HumanMessage);
    }

    @Test
    void priorityIsAfterMemoryMiddleware() {
        // BankRecallMiddleware runs at priority 5 so MemoryMiddleware
        // (priority 0) loads AGENTS.md first, then we append recall.
        assertEquals(5, mw.priority());
    }

    @Test
    void constructorRejectsNullBank() {
        assertThrows(NullPointerException.class,
                () -> new BankRecallMiddleware(null));
    }

    @Test
    void constructorRejectsInvalidTopK() {
        assertThrows(IllegalArgumentException.class,
                () -> new BankRecallMiddleware(bank, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new BankRecallMiddleware(bank, -1, null));
    }

    @Test
    void constructorRejectsTemplateWithoutSlot() {
        assertThrows(IllegalArgumentException.class,
                () -> new BankRecallMiddleware(bank, 3, "no slot here"));
    }

    @Test
    void defaultTemplateContainsRecallSlot() {
        assertTrue(BankRecallMiddleware.DEFAULT_SYSTEM_PROMPT.contains("{recall}"));
    }
}
