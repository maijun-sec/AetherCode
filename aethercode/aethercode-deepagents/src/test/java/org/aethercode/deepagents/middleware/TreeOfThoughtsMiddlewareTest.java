package org.aethercode.deepagents.middleware;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.deepagents.middleware.TreeOfThoughtsMiddleware.Branch;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R240 (O-4): tests for {@link TreeOfThoughtsMiddleware}. The
 * middleware exposes three "tools" but is exercised in unit-test
 * form by simulating the model output and asserting the
 * resulting {@link AgentState}.
 */
class TreeOfThoughtsMiddlewareTest {

    @Test
    void nameIsStable() {
        assertEquals("TreeOfThoughtsMiddleware", new TreeOfThoughtsMiddleware().name());
    }

    @Test
    void beforeModelBumpsDepth() {
        TreeOfThoughtsMiddleware mw = new TreeOfThoughtsMiddleware();
        AgentState start = AgentState.empty();
        AgentState after1 = mw.beforeModel(start, null);
        AgentState after2 = mw.beforeModel(after1, null);
        assertEquals(1, after1.extensions().get(TreeOfThoughtsMiddleware.DEPTH_KEY));
        assertEquals(2, after2.extensions().get(TreeOfThoughtsMiddleware.DEPTH_KEY));
    }

    @Test
    void thinkBranchesRecordsAndAssignesIds() {
        TreeOfThoughtsMiddleware mw = new TreeOfThoughtsMiddleware();
        AgentState start = AgentState.empty();
        AIMessage ai = aiWithToolCall(
                "tool-1",
                TreeOfThoughtsMiddleware.TOOL_THINK_BRANCHES,
                Map.of(
                        "rationale", "two possible approaches",
                        "branches", List.of("use Z3 to verify", "write a quick prototype first")
                ));
        AgentState next = mw.afterModel(start, ai, null);
        List<Branch> got = TreeOfThoughtsMiddleware.readBranches(next);
        assertEquals(2, got.size());
        assertEquals("b0", got.get(0).id());
        assertEquals("b1", got.get(1).id());
        assertEquals("use Z3 to verify", got.get(0).text());
        assertEquals("write a quick prototype first", got.get(1).text());
        assertEquals("two possible approaches", got.get(0).rationale());
        assertFalse(got.get(0).dead());
    }

    @Test
    void thinkBranchesTooFewIsIgnored() {
        TreeOfThoughtsMiddleware mw = new TreeOfThoughtsMiddleware();
        AgentState start = AgentState.empty();
        AIMessage ai = aiWithToolCall("t", TreeOfThoughtsMiddleware.TOOL_THINK_BRANCHES,
                Map.of("branches", List.of("only one")));
        AgentState next = mw.afterModel(start, ai, null);
        assertTrue(TreeOfThoughtsMiddleware.readBranches(next).isEmpty());
    }

    @Test
    void thinkBranchesTooManyIsIgnored() {
        TreeOfThoughtsMiddleware mw = new TreeOfThoughtsMiddleware();
        AgentState start = AgentState.empty();
        List<String> six = List.of("a","b","c","d","e","f");
        AIMessage ai = aiWithToolCall("t", TreeOfThoughtsMiddleware.TOOL_THINK_BRANCHES,
                Map.of("branches", six));
        AgentState next = mw.afterModel(start, ai, null);
        assertTrue(TreeOfThoughtsMiddleware.readBranches(next).isEmpty());
    }

    @Test
    void selectBranchUpdatesCurrent() {
        TreeOfThoughtsMiddleware mw = new TreeOfThoughtsMiddleware();
        AgentState start = stateWithBranches(
                new Branch("b0", "use Z3", "", false),
                new Branch("b1", "prototype first", "", false));
        AIMessage ai = aiWithToolCall("t", TreeOfThoughtsMiddleware.TOOL_SELECT_BRANCH,
                Map.of("branchId", "b1", "reason", "faster"));
        AgentState next = mw.afterModel(start, ai, null);
        assertEquals("b1", TreeOfThoughtsMiddleware.readCurrent(next));
    }

    @Test
    void selectBranchUnknownIgnored() {
        TreeOfThoughtsMiddleware mw = new TreeOfThoughtsMiddleware();
        AgentState start = stateWithBranches(
                new Branch("b0", "use Z3", "", false));
        AIMessage ai = aiWithToolCall("t", TreeOfThoughtsMiddleware.TOOL_SELECT_BRANCH,
                Map.of("branchId", "bZZ", "reason", "nope"));
        AgentState next = mw.afterModel(start, ai, null);
        assertNull(TreeOfThoughtsMiddleware.readCurrent(next));
    }

    @Test
    void pruneBranchMarksDead() {
        TreeOfThoughtsMiddleware mw = new TreeOfThoughtsMiddleware();
        AgentState start = stateWithBranches(
                new Branch("b0", "use Z3", "", false),
                new Branch("b1", "prototype first", "", false));
        AIMessage ai = aiWithToolCall("t", TreeOfThoughtsMiddleware.TOOL_PRUNE_BRANCH,
                Map.of("branchId", "b0", "reason", "tooling missing"));
        AgentState next = mw.afterModel(start, ai, null);
        List<Branch> got = TreeOfThoughtsMiddleware.readBranches(next);
        assertEquals(2, got.size());
        assertTrue(got.get(0).dead());
        assertEquals("tooling missing", got.get(0).rationale());
        assertFalse(got.get(1).dead());
    }

    @Test
    void multipleToolUsesInOneMessageAreAppliedInOrder() {
        TreeOfThoughtsMiddleware mw = new TreeOfThoughtsMiddleware();
        AgentState start = AgentState.empty();
        AIMessage ai = aiWithMultipleToolCalls(List.of(
                Map.of("id", "t1", "tool", TreeOfThoughtsMiddleware.TOOL_THINK_BRANCHES,
                        "args", Map.of("rationale", "r",
                                "branches", List.of("alpha", "beta", "gamma"))),
                Map.of("id", "t2", "tool", TreeOfThoughtsMiddleware.TOOL_SELECT_BRANCH,
                        "args", Map.of("branchId", "b1", "reason", "r")),
                Map.of("id", "t3", "tool", TreeOfThoughtsMiddleware.TOOL_PRUNE_BRANCH,
                        "args", Map.of("branchId", "b0", "reason", "r"))
        ));
        AgentState next = mw.afterModel(start, ai, null);
        List<Branch> got = TreeOfThoughtsMiddleware.readBranches(next);
        assertEquals(3, got.size());
        assertTrue(got.get(0).dead(), "b0 should be pruned");
        assertEquals("b1", TreeOfThoughtsMiddleware.readCurrent(next));
    }

    @Test
    void longBranchTextIsTruncated() {
        TreeOfThoughtsMiddleware mw = new TreeOfThoughtsMiddleware();
        AgentState start = AgentState.empty();
        String longText = "x".repeat(1000);
        AIMessage ai = aiWithToolCall("t", TreeOfThoughtsMiddleware.TOOL_THINK_BRANCHES,
                Map.of("branches", List.of(longText, "short")));
        AgentState next = mw.afterModel(start, ai, null);
        List<Branch> got = TreeOfThoughtsMiddleware.readBranches(next);
        assertEquals(2, got.size());
        assertEquals(TreeOfThoughtsMiddleware.MAX_BRANCH_TEXT,
                got.get(0).text().length(),
                "long branch must be truncated to MAX_BRANCH_TEXT");
    }

    @Test
    void otherToolsAreIgnored() {
        TreeOfThoughtsMiddleware mw = new TreeOfThoughtsMiddleware();
        AgentState start = AgentState.empty();
        AIMessage ai = aiWithToolCall("t", "write_todos",
                Map.of("todos", List.of()));
        AgentState next = mw.afterModel(start, ai, null);
        assertTrue(TreeOfThoughtsMiddleware.readBranches(next).isEmpty());
    }

    @Test
    void branchRecordToMapIsStable() {
        Branch b = new Branch("b0", "text", "rat", false);
        Map<String, Object> m = b.toMap();
        assertEquals("b0", m.get("id"));
        assertEquals("text", m.get("text"));
        assertEquals("rat", m.get("rationale"));
        assertEquals(false, m.get("dead"));
    }

    @Test
    void defaultPromptFragmentMentionsThinkBranches() {
        assertTrue(TreeOfThoughtsMiddleware.DEFAULT_PROMPT_FRAGMENT.contains("think_branches"));
    }

    // -- helpers -----------------------------------------------------

    /** Simulate an AI message containing exactly one tool-use block. */
    private static AIMessage aiWithToolCall(String id, String name, Map<String, Object> input) {
        ContentBlock.ToolUseBlock tu = new ContentBlock.ToolUseBlock(id, name, input);
        return new AIMessage("ai-msg-" + id, List.of(tu), java.util.Optional.empty(), Map.of());
    }

    /** Simulate an AI message with several tool-use blocks. */
    private static AIMessage aiWithMultipleToolCalls(List<Map<String, Object>> calls) {
        List<ContentBlock> blocks = new java.util.ArrayList<>();
        for (Map<String, Object> c : calls) {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) c.get("args");
            blocks.add(new ContentBlock.ToolUseBlock(
                    (String) c.get("id"),
                    (String) c.get("tool"),
                    args == null ? Map.of() : args));
        }
        return new AIMessage("ai-msg-multi", blocks, java.util.Optional.empty(), Map.of());
    }

    private static AgentState stateWithBranches(Branch... branches) {
        return AgentState.empty().withExtension(
                TreeOfThoughtsMiddleware.BRANCHES_KEY, List.of(branches));
    }
}
