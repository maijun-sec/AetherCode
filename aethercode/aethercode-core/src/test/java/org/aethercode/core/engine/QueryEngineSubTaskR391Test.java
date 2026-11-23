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
 * R391 tests for {@link QueryEngine#emitSubTaskTransitions} — the
 * sub-task's TEXT is part of its identity from the renderer's point of
 * view.
 *
 * <p><b>THE BUG.</b> The synthesised flat-todo sub-task id is
 * POSITIONAL ({@code auto-{index}}), so the same slot can hold a
 * completely different piece of work after the model rewrites its plan.
 * The engine tracked only the sub-task's STATUS, never its TEXT, so a
 * re-worded todo produced no event at all — the card's title stayed
 * frozen at the first wording while the plan moved on. The user saw a
 * step titled "运行 mvn test 直到全绿并复核 diff" whose actual content
 * was "mvn clean test 从零全量验证 + 复核最终文件清单".
 *
 * <p>Worse, the two shapes (model-declared {@code subtasks[]} and the
 * synthesised flat todo) carried TWO hand-written copies of the same
 * state machine. They had already drifted.
 *
 * <p><b>THE FIX.</b> One shared emitter
 * ({@code emitSubTaskTransitionsFor}) tracks the TEXT alongside the
 * STATUS and re-announces the sub-task when the text changes — with
 * the PREVIOUS status, so a re-worded item is not mistaken for a
 * re-opened one.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>1. a flat todo whose text is re-worded (status unchanged) →
 *       a fresh SubTaskStart carrying the NEW text and the OLD status;</li>
 *   <li>2. a flat todo whose text AND status both change → the text
 *       update and the status transition both fire;</li>
 *   <li>3. a flat todo whose text changes while it becomes
 *       in_progress → exactly ONE SubTaskStart (not two);</li>
 *   <li>4. the model-declared shape gets the same treatment (the two
 *       shapes share one emitter);</li>
 *   <li>5. an unchanged todo list still emits nothing (no event
 *       flood).</li>
 * </ul>
 */
class QueryEngineSubTaskR391Test {

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

    private static Map<String, Object> flat(String content, String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("content", content);
        m.put("status", status);
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

    private static Map<String, Object> sub(String id, String content, String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("content", content);
        m.put("status", status);
        return m;
    }

    /** Compact rendering of the emitted events, e.g.
     *  {@code START:0:auto-0:in_progress:搭建 Maven 项目骨架}. */
    private List<String> eventTypes() {
        return sink.stream().map(e -> {
            if (e instanceof StreamEvent.SubTaskStart s) {
                return "START:" + s.taskId() + ":" + s.subTaskId() + ":" + s.status() + ":" + s.content();
            }
            if (e instanceof StreamEvent.SubTaskEnd e2) {
                return "END:" + e2.taskId() + ":" + e2.subTaskId() + ":" + e2.status()
                        + ":" + (e2.summary() == null ? "" : e2.summary());
            }
            return e.getClass().getSimpleName();
        }).toList();
    }

    // ---- 1. re-worded flat todo → Start with the NEW text, OLD status ----
    @Test
    void flatTodo_textRewritten_reAnnouncesWithPreviousStatus() {
        state.setTodoList(List.of(flat("运行 mvn test 直到全绿并复核 diff", "completed")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of(
                "START:0:auto-0:completed:运行 mvn test 直到全绿并复核 diff",
                "END:0:auto-0:completed:"
        ), eventTypes(), "R391: first sight of a flat todo");

        sink.clear();
        // The model re-worded the item but did not change its status.
        state.setTodoList(List.of(flat("mvn clean test 从零全量验证 + 复核最终文件清单", "completed")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(
                List.of("START:0:auto-0:completed:mvn clean test 从零全量验证 + 复核最终文件清单"),
                eventTypes(),
                "R391: a re-worded flat todo must re-announce itself with the NEW text and the "
                        + "PREVIOUS status — a re-worded item is not a re-opened one, so the "
                        + "status must not jump back to in_progress");
    }

    // ---- 2. text AND status both change → both fire ----
    @Test
    void flatTodo_textAndStatusChanged_emitsBoth() {
        state.setTodoList(List.of(flat("旧标题", "pending")));
        engine.emitSubTaskTransitions(sink::add);
        sink.clear();

        state.setTodoList(List.of(flat("新标题", "completed")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(
                List.of(
                        "START:0:auto-0:pending:新标题",
                        "END:0:auto-0:completed:"
                ),
                eventTypes(),
                "R391: when the text AND the status move, the title update (with the previous "
                        + "status) and the terminal transition must both fire");
    }

    // ---- 3. text changes while the item becomes in_progress → ONE Start ----
    @Test
    void flatTodo_textChangedAndBecomesActive_emitsSingleStart() {
        state.setTodoList(List.of(flat("旧标题", "pending")));
        engine.emitSubTaskTransitions(sink::add);
        sink.clear();

        state.setTodoList(List.of(flat("新标题", "in_progress")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(
                List.of("START:0:auto-0:in_progress:新标题"),
                eventTypes(),
                "R391: one Start must carry BOTH the new text and the new status — two Starts "
                        + "would announce the same card twice");
    }

    // ---- 4. the model-declared shape gets the same treatment ----
    @Test
    void declaredSubTask_textRewritten_reAnnounces() {
        state.setTodoList(List.of(nested("父任务", "in_progress",
                List.of(sub("s1", "旧的小标题", "in_progress")))));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("START:0:s1:in_progress:旧的小标题"), eventTypes(),
                "R391: first sight of a declared sub-task");

        sink.clear();
        state.setTodoList(List.of(nested("父任务", "in_progress",
                List.of(sub("s1", "新的小标题", "in_progress")))));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("START:0:s1:in_progress:新的小标题"), eventTypes(),
                "R391: the DECLARED shape must get the same text-tracking as the flat one — "
                        + "they share one emitter, so they cannot drift apart");
    }

    // ---- 5. an unchanged todo list still emits nothing ----
    @Test
    void unchangedTodoList_emitsNothing() {
        state.setTodoList(List.of(flat("标题", "in_progress")));
        engine.emitSubTaskTransitions(sink::add);
        sink.clear();
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of(), eventTypes(),
                "R391: the text tracking must not turn the emitter into an event flood");
    }

    // ---- 6. a dropped todo still closes with the "removed" summary ----
    //     (the desktop decides to DROP the card; the engine's summary
    //      is the signal it keys on).
    @Test
    void droppedFlatTodo_closesWithRemovedSummary() {
        state.setTodoList(List.of(flat("留下来的项", "pending"), flat("会被删掉的项", "pending")));
        engine.emitSubTaskTransitions(sink::add);
        sink.clear();

        // The model dropped the SECOND item. The first one keeps its
        // slot (and its text), the second one's slot disappears.
        state.setTodoList(List.of(flat("留下来的项", "pending")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("END:1:auto-1:skipped:sub-task removed from todo list"), eventTypes(),
                "R391: a dropped todo must still close with the 'removed from todo list' "
                        + "summary — that string is the desktop's signal to drop the card");
    }

    // ---- 7. when the model drops an EARLIER item, the later items
    //      slide up a slot and their titles follow them ----
    @Test
    void droppedEarlierFlatTodo_laterItemsSlideUpAndRetitle() {
        state.setTodoList(List.of(
                flat("第一项", "completed"),
                flat("第二项", "completed")));
        engine.emitSubTaskTransitions(sink::add);
        sink.clear();

        // "第一项" is gone; "第二项" now occupies slot 0. The card in
        // slot 0 must now carry "第二项"'s title — that is what the
        // R391 text tracking buys: the id is positional, so without it
        // the card would keep showing the DELETED item's title.
        state.setTodoList(List.of(flat("第二项", "completed")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(
                List.of(
                        "START:0:auto-0:completed:第二项",
                        "END:1:auto-1:skipped:sub-task removed from todo list"
                ),
                eventTypes(),
                "R391: a slot whose todo changed must re-announce itself with the new title, "
                        + "and the slot that vanished must close with the 'removed' summary");
    }
}
