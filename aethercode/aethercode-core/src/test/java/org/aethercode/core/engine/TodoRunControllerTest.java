package org.aethercode.core.engine;

import org.aethercode.core.app.AppState;
import org.aethercode.core.message.ContentBlock;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * unit tests for {@link TodoRunController}.
 *
 * Covers the three main paths:
 *   1. Below soft threshold → Continue.
 *   2. Hit soft threshold once → AskLlm with bumped threshold.
 *   3. After max bumps → AwaitUser.
 *
 * Plus the side conditions: no in_progress todo, todo changes,
 * reset between queries, configure() at runtime.
 */
class TodoRunControllerTest {

    private static ContentBlock.ToolUseBlock tool(String name) {
        return new ContentBlock.ToolUseBlock("id-" + System.nanoTime(), name, Map.of());
    }

    private static AppState stateWithTodo(String content) {
        AppState s = new AppState("sess", Path.of("/tmp"));
        List<Map<String, Object>> todos = new ArrayList<>();
        Map<String, Object> t = new HashMap<>();
        t.put("content", content);
        t.put("status", "in_progress");
        todos.add(t);
        s.setTodoList(todos);
        return s;
    }

    private static AppState stateWithNoTodos() {
        return new AppState("sess", Path.of("/tmp"));
    }

    @Test
    void belowThresholdReturnsContinue() {
        TodoRunController c = new TodoRunController(5, 5, 3);
        AppState s = stateWithTodo("do the thing");
        for (int i = 0; i < 5; i++) {
            var v = c.check(List.of(tool("read")), List.of("read"), 0, s);
            assertInstanceOf(TodoRunController.Verdict.Continue.class, v,
                    "step " + (i + 1) + " should be below threshold");
        }
    }

    @Test
    void atThresholdTriggersAskLlm() {
        // softThreshold=3 means steps 1-3 are Continue, step 4 fires AskLlm
        // (the threshold is the count of steps the model can take BEFORE we ask).
        TodoRunController c = new TodoRunController(3, 10, 5);
        AppState s = stateWithTodo("refactor the auth module");
        c.check(List.of(tool("read")), List.of("read"), 0, s); // 1
        c.check(List.of(tool("read")), List.of("read"), 0, s); // 2
        c.check(List.of(tool("read")), List.of("read"), 0, s); // 3
        var v = c.check(List.of(tool("edit")), List.of("edit"), 0, s); // 4 -> AskLlm
        assertInstanceOf(TodoRunController.Verdict.AskLlm.class, v);
        TodoRunController.Verdict.AskLlm ask = (TodoRunController.Verdict.AskLlm) v;
        assertEquals(13, ask.newSoftThreshold(), "3 + bumpIncrement(10) = 13");
        assertTrue(ask.summary().contains("refactor the auth module"));
        assertEquals(1, c.bumpsIssued());
    }

    @Test
    void afterMaxBumpsTriggersAwaitUser() {
        // softThreshold=2, bump=5, max=2:
        //   steps 1-2 = Continue
        //   step 3 = AskLlm #1, threshold now 7
        //   steps 4-7 = Continue
        //   step 8 = AskLlm #2, threshold now 12
        //   steps 9-12 = Continue
        //   step 13 = AwaitUser (max bumps hit, escalate to user)
        TodoRunController c = new TodoRunController(2, 5, 2);
        AppState s = stateWithTodo("audit the codebase");
        // Steps 1-2: Continue
        c.check(List.of(tool("read")), List.of("read"), 0, s);
        c.check(List.of(tool("read")), List.of("read"), 0, s);
        // Step 3: AskLlm #1
        var first = c.check(List.of(tool("read")), List.of("read"), 0, s);
        assertInstanceOf(TodoRunController.Verdict.AskLlm.class, first);
        assertEquals(1, c.bumpsIssued());
        // Steps 4-7: Continue (threshold now 7)
        for (int i = 0; i < 4; i++) c.check(List.of(tool("read")), List.of("read"), 0, s);
        // Step 8: AskLlm #2 (8 > 7)
        var second = c.check(List.of(tool("read")), List.of("read"), 0, s);
        assertInstanceOf(TodoRunController.Verdict.AskLlm.class, second,
                "step 8 should trigger AskLlm #2 (threshold now 12, bumpsIssued=2)");
        assertEquals(2, c.bumpsIssued());
        // Steps 9-12: Continue (threshold now 12)
        for (int i = 0; i < 4; i++) c.check(List.of(tool("read")), List.of("read"), 0, s);
        // Step 13: AwaitUser (max bumps hit)
        var finalV = c.check(List.of(tool("read")), List.of("read"), 0, s);
        assertInstanceOf(TodoRunController.Verdict.AwaitUser.class, finalV,
                "after max bumps, should escalate to user");
    }

    @Test
    void noInProgressTodoResetsCounter() {
        TodoRunController c = new TodoRunController(2, 5, 3);
        AppState empty = stateWithNoTodos();
        for (int i = 0; i < 10; i++) {
            var v = c.check(List.of(tool("read")), List.of("read"), 0, empty);
            // No in_progress todo = no enforcement. Just Continue.
            assertInstanceOf(TodoRunController.Verdict.Continue.class, v);
        }
    }

    @Test
    void todoChangeResetsStepCount() {
        TodoRunController c = new TodoRunController(2, 5, 3);
        AppState s = stateWithTodo("first task");
        c.check(List.of(tool("read")), List.of("read"), 0, s); // 1
        c.check(List.of(tool("read")), List.of("read"), 0, s); // 2
        // Switch to a different in_progress todo.
        s.setTodoList(List.of(todoMap("second task")));
        // Should NOT trigger yet — counter reset.
        var v = c.check(List.of(tool("read")), List.of("read"), 0, s);
        assertInstanceOf(TodoRunController.Verdict.Continue.class, v,
                "switching to a new todo must reset the step counter");
    }

    @Test
    void resetClearsBumpsAndThreshold() {
        TodoRunController c = new TodoRunController(2, 5, 3);
        AppState s = stateWithTodo("task");
        c.check(List.of(tool("read")), List.of("read"), 0, s);
        c.check(List.of(tool("read")), List.of("read"), 0, s);
        c.check(List.of(tool("read")), List.of("read"), 0, s);
        assertEquals(1, c.bumpsIssued());
        c.reset();
        assertEquals(0, c.bumpsIssued());
        assertEquals(2, c.softThreshold());
    }

    @Test
    void configureOverridesAll() {
        TodoRunController c = new TodoRunController(2, 5, 3);
        c.configure(10, 20, 7);
        assertEquals(10, c.initialSoftThreshold());
        assertEquals(20, c.bumpIncrement());
        assertEquals(7, c.maxBumpsBeforeUser());
        c.reset();
        assertEquals(10, c.softThreshold());
    }

    @Test
    void builderValidatesInputs() {
        assertThrows(IllegalArgumentException.class, () -> new TodoRunController(0, 5, 3));
        assertThrows(IllegalArgumentException.class, () -> new TodoRunController(2, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> new TodoRunController(2, 5, 0));
    }

    @Test
    void askLlmPromptMentionsTodoAndThresholds() {
        String prompt = TodoRunController.buildAskLlmPrompt(
                "the goal", 35, 30, 60);
        assertTrue(prompt.contains("the goal"));
        assertTrue(prompt.contains("35"));
        assertTrue(prompt.contains("30"));
        assertTrue(prompt.contains("60"));
        // Should mention all four options A/B/C/D.
        assertTrue(prompt.contains("(A)"));
        assertTrue(prompt.contains("(B)"));
        assertTrue(prompt.contains("(C)"));
        assertTrue(prompt.contains("(D)"));
    }

    @Test
    void summaryMentionsErrorsAndTools() {
        String s = TodoRunController.buildSummary(
                "todo", 31, 30, List.of("bash", "read_file"), 2);
        assertTrue(s.contains("todo"));
        assertTrue(s.contains("31"));
        assertTrue(s.contains("bash"));
        assertTrue(s.contains("read_file"));
        assertTrue(s.contains("2 tool error"));
    }

    private static Map<String, Object> todoMap(String content) {
        Map<String, Object> t = new HashMap<>();
        t.put("content", content);
        t.put("status", "in_progress");
        return t;
    }
}
