/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.engine;

import org.aethercode.core.app.AppState;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R740 source-pin tests for {@link QueryEngine#emitSubTaskTransitions}
 * flat-todo branch.
 *
 * <p>Pre-R740, {@code emitSubTaskTransitions} only emitted
 * {@link StreamEvent.SubTaskStart} / {@link StreamEvent.SubTaskEnd}
 * for top-level todos that carried a {@code subtasks[]} array. The
 * common case in the wild — and the case the user's RAG session has
 * been hitting for the entire transcript — is a flat
 * {@code todo_write({todos: [{content, status}, ...]})} call where
 * each top-level todo IS the work item, with no further
 * decomposition. Pre-R740 those flat todos were silently skipped
 * (the {@code if (!(subs instanceof java.util.List<?>)) continue;}
 * branch), so the desktop MessageList SubTaskCard area stayed empty
 * even though the RightPanel "Plan" tab was rendering the same
 * items as completed.
 *
 * <p>R740 changes {@code emitSubTaskTransitions} so that, when a
 * top-level todo has no {@code subtasks} key, it synthesizes ONE
 * {@link StreamEvent.SubTaskStart} for that todo under the
 * synthetic id {@code auto-{idx}}, with the todo's own
 * {@code content} / {@code status} / {@code summary}. Status
 * transitions (in_progress / completed / failed / skipped) follow
 * the same diff logic as the nested branch.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>1. first sight of a flat pending todo → emits SubTaskStart(pending)</li>
 *   <li>2. flat todo transitions pending → in_progress → completed
 *       → emits Start(in_progress) then End(completed)</li>
 *   <li>3. mix: one nested-with-subtasks top-level + one flat →
 *       both emit correctly with distinct keys (0:nested-id and 1:auto-0)</li>
 *   <li>4. flat todos with no content → skipped silently, no events</li>
 *   <li>5. repeating the same flat todos across calls → no duplicate events</li>
 *   <li>6. flat todo transitions in a single emit (first sight
 *       directly as completed) → emits Start then End in same call</li>
 * </ul>
 */
class QueryEngineSubTaskFlatTodoR740Test {

    private AppState state;
    private QueryEngine engine;
    private List<StreamEvent> sink;

    @BeforeEach
    void setUp() {
        state = new AppState("sess", Path.of("/tmp"));
        ChatClient stub = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                return Stream.empty();
            }
            @Override public String modelId() { return "stub"; }
        };
        org.aethercode.core.engine.PermissionPolicy allowAll =
                (tool, input, ctx) -> CompletableFuture.completedFuture(
                        new org.aethercode.core.permission.PermissionResult.Allow(Map.of()));
        org.aethercode.core.engine.StreamingToolExecutor noopExec =
                new org.aethercode.core.engine.StreamingToolExecutor(allowAll, 1) {
                    @Override
                    public java.util.stream.Stream<Event> run(
                            List<ContentBlock.ToolUseBlock> batch, AppState appState) {
                        List<Event> events = new ArrayList<>();
                        events.add(new Event.BatchEnd());
                        return events.stream();
                    }
                };
        engine = new QueryEngine(state, stub, allowAll, "", null, noopExec, null, null);
        sink = new ArrayList<>();
    }

    /** Helper: build a flat todo list (no `subtasks` field) with
     *  each entry as {content, status, summary?}. This is the shape
     *  the user's RAG todo_write calls have been emitting — the
     *  shape that pre-R740 silently dropped. */
    private void setFlatTodos(List<Map<String, Object>> flatTodos) {
        state.setTodoList(flatTodos);
    }

    private static Map<String, Object> flat(String content, String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("content", content);
        m.put("status", status);
        return m;
    }

    private static Map<String, Object> flat(String content, String status, String summary) {
        Map<String, Object> m = flat(content, status);
        if (summary != null) m.put("summary", summary);
        return m;
    }

    private static Map<String, Object> nested(String content, String status,
                                               List<Map<String, Object>> subs) {
        Map<String, Object> m = new HashMap<>();
        m.put("content", content);
        m.put("status", status);
        m.put("subtasks", subs);
        return m;
    }

    private List<String> eventTypes() {
        return sink.stream().map(e -> {
            if (e instanceof StreamEvent.SubTaskStart s) {
                return "START:" + s.taskId() + ":" + s.subTaskId() + ":" + s.status();
            }
            if (e instanceof StreamEvent.SubTaskEnd e2) {
                return "END:" + e2.taskId() + ":" + e2.subTaskId() + ":" + e2.status();
            }
            return e.getClass().getSimpleName();
        }).toList();
    }

    // ---- 1. first sight (pending) on a flat todo ----
    @Test
    void flatTodo_firstSight_emitsStart() {
        setFlatTodos(List.of(
                flat("梳理上一轮决策", "pending"),
                flat("读现状文件", "pending")
        ));
        engine.emitSubTaskTransitions(sink::add);
        // Two flat todos → two SubTaskStart events, both pending,
        // ids `auto-0` and `auto-1`. Pre-R740: zero events.
        assertEquals(
                List.of("START:0:auto-0:pending", "START:1:auto-1:pending"),
                eventTypes(),
                "R740: each flat top-level todo should emit a synthetic SubTaskStart under auto-{idx}"
        );
    }

    // ---- 2. transition pending → in_progress → completed ----
    @Test
    void flatTodo_pendingToInProgressToCompleted() {
        setFlatTodos(List.of(flat("梳理上一轮决策", "pending")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("START:0:auto-0:pending"), eventTypes());

        sink.clear();
        setFlatTodos(List.of(flat("梳理上一轮决策", "in_progress")));
        engine.emitSubTaskTransitions(sink::add);
        // The pending → in_progress transition fires a fresh
        // SubTaskStart with the in_progress status (same as the
        // nested branch's re-open behaviour).
        assertEquals(List.of("START:0:auto-0:in_progress"), eventTypes(),
                "R740: pending → in_progress should re-fire SubTaskStart with new status");

        sink.clear();
        setFlatTodos(List.of(flat("梳理上一轮决策", "completed", "all decisions captured")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("END:0:auto-0:completed"), eventTypes(),
                "R740: in_progress → completed should emit SubTaskEnd only, no extra Start");
    }

    // ---- 3. mix: nested-with-subtasks AND flat coexist ----
    @Test
    void flatAndNested_mixedCoexist() {
        Map<String, Object> nestedSub = new HashMap<>();
        nestedSub.put("id", "p2-1");
        nestedSub.put("content", "加 PipelineFactory");
        nestedSub.put("status", "in_progress");
        setFlatTodos(List.of(
                nested("P2 工厂接入", "in_progress", List.of(nestedSub)),
                flat("P3 总结", "pending")
        ));
        engine.emitSubTaskTransitions(sink::add);
        // The nested sub-task emits under (0, p2-1). The flat todo
        // emits under (1, auto-0). Both must show up.
        assertEquals(
                List.of("START:0:p2-1:in_progress", "START:1:auto-1:pending"),
                eventTypes(),
                "R740: nested sub-tasks and flat todos must produce distinct SubTaskStart events"
        );
    }

    // ---- 4. flat todos with no content are skipped silently ----
    @Test
    void flatTodo_missingContent_isSkipped() {
        // Two invalid entries (no content) + one valid entry.
        // The invalid ones must NOT emit Start, and the valid one
        // must emit under the correct auto-{idx} (the third item's
        // idx is 2).
        List<Map<String, Object>> todos = new ArrayList<>();
        Map<String, Object> bad1 = new HashMap<>();
        bad1.put("status", "pending");
        // no content
        todos.add(bad1);
        todos.add(flat("", "pending")); // empty content is also "no content"
        todos.add(flat("总结", "pending"));
        setFlatTodos(todos);
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("START:2:auto-2:pending"), eventTypes(),
                "R740: a flat todo with no content must not emit SubTaskStart");
    }

    // ---- 5. repeating the same flat todos is a no-op ----
    @Test
    void flatTodo_sameStatus_acrossCalls_emitsNothing() {
        setFlatTodos(List.of(flat("梳理", "in_progress")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(1, sink.size());
        sink.clear();
        // Same call again — same status → no new events.
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(0, sink.size(),
                "R740: re-emitting the same todo list must be a no-op (lastSubTaskStatus dedup)");
    }

    // ---- 6. first sight directly as terminal emits Start + End in the same call ----
    @Test
    void flatTodo_firstSightTerminal_emitsStartAndEnd() {
        // Model never bothered with in_progress — the todo shows up
        // already completed (e.g. a one-shot batch update at the end
        // of the run).
        setFlatTodos(List.of(
                flat("P10 mvn -o test 全量验证", "completed", "397/397"),
                flat("总结全部修改", "completed", "已写 docs/plan.md")
        ));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(
                List.of(
                        "START:0:auto-0:completed",
                        "END:0:auto-0:completed",
                        "START:1:auto-1:completed",
                        "END:1:auto-1:completed"
                ),
                eventTypes(),
                "R740: a flat todo that arrives already in a terminal status must emit " +
                "Start(terminal) + End in the SAME call (matching the nested branch behaviour)"
        );
        // The End events carry the summary so the renderer can show it.
        StreamEvent.SubTaskEnd end0 = (StreamEvent.SubTaskEnd) sink.get(1);
        assertEquals("397/397", end0.summary());
        StreamEvent.SubTaskEnd end1 = (StreamEvent.SubTaskEnd) sink.get(3);
        assertEquals("已写 docs/plan.md", end1.summary());
    }
}