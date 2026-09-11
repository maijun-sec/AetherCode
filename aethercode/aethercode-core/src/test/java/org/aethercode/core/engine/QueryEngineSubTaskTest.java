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
 * unit tests for the sub-task state machine in
 * {@link QueryEngine#emitSubTaskTransitions(java.util.function.Consumer)}.
 *
 * <p>The state machine is the engine's "scheduling strategy" for
 * sub-tasks: it observes the model's {@code todo_write} and
 * {@code sub_todo_write} calls, and emits {@link StreamEvent.SubTaskStart}
 * / {@link StreamEvent.SubTaskEnd} events for the UI to consume.
 *
 * <p>The state machine is intentionally lenient: the model may
 * declare a sub-task in {@code pending} and then directly
 * transition to {@code completed} without ever calling
 * {@code in_progress}. The engine handles all these cases
 * without exploding.
 *
 * <p>Tests cover the eight core transitions plus the structural
 * invariants (event fields, status strings, dedup across calls).
 */
class QueryEngineSubTaskTest {

    private AppState state;
    private QueryEngine engine;
    private List<StreamEvent> sink;

    @BeforeEach
    void setUp() {
        state = new AppState("sess", Path.of("/tmp"));
        // Stub ChatClient + executor (we only exercise the package-private
        // emitSubTaskTransitions, not the full query() flow).
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

    /** Helper: build a single-task todo list with the given subtasks.
     *  Each subtask is {id, content, status, summary?}. */
    private void setTodos(List<Map<String, Object>> subtasks) {
        List<Map<String, Object>> todos = new ArrayList<>();
        Map<String, Object> t = new HashMap<>();
        t.put("content", "top");
        t.put("status", "in_progress");
        t.put("subtasks", subtasks);
        todos.add(t);
        state.setTodoList(todos);
    }

    private static Map<String, Object> sub(String id, String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("content", "do " + id);
        m.put("status", status);
        return m;
    }

    private static Map<String, Object> sub(String id, String status, String summary) {
        Map<String, Object> m = sub(id, status);
        if (summary != null) m.put("summary", summary);
        return m;
    }

    private List<String> eventTypes() {
        return sink.stream().map(e -> {
            if (e instanceof StreamEvent.SubTaskStart) return "START:" + ((StreamEvent.SubTaskStart) e).subTaskId();
            if (e instanceof StreamEvent.SubTaskEnd) return "END:" + ((StreamEvent.SubTaskEnd) e).subTaskId() + ":" + ((StreamEvent.SubTaskEnd) e).status();
            return e.getClass().getSimpleName();
        }).toList();
    }

    // ---- 1. First sight (pending) ----

    @Test
    void firstSight_pending_emitsStartOnly() {
        setTodos(List.of(sub("a", "pending")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("START:a"), eventTypes());
        StreamEvent.SubTaskStart s = (StreamEvent.SubTaskStart) sink.get(0);
        assertEquals(0, s.taskId());
        assertEquals("a", s.subTaskId());
        assertEquals("do a", s.content());
        assertEquals("pending", s.status());
    }

    // ---- 2. First sight (in_progress) ----

    @Test
    void firstSight_inProgress_emitsStartOnly() {
        setTodos(List.of(sub("a", "in_progress")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("START:a"), eventTypes());
    }

    // ---- 3. First sight (terminal: completed) ----
    // Model skips in_progress and goes straight to completed. The engine
    // must emit Start AND End so the UI shows a complete card with the
    // summary.

    @Test
    void firstSight_completed_emitsStartAndEnd() {
        setTodos(List.of(sub("a", "completed", "all done")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("START:a", "END:a:completed"), eventTypes());
        StreamEvent.SubTaskEnd e = (StreamEvent.SubTaskEnd) sink.get(1);
        assertEquals("all done", e.summary());
    }

    // ---- 4. First sight (terminal: failed / skipped) ----

    @Test
    void firstSight_failed_emitsStartAndEnd() {
        setTodos(List.of(sub("a", "failed", "boom")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("START:a", "END:a:failed"), eventTypes());
    }

    // ---- 5. Stable state across multiple calls (idempotent) ----

    @Test
    void sameStatus_acrossCalls_emitsNothing() {
        setTodos(List.of(sub("a", "in_progress")));
        engine.emitSubTaskTransitions(sink::add);
        sink.clear();
        // Second call: same status, no new events.
        engine.emitSubTaskTransitions(sink::add);
        assertTrue(sink.isEmpty(), "stable state should emit no events, got: " + eventTypes());
    }

    // ---- 6. pending -> in_progress (emits Start with in_progress) ----
    // The UI needs to know which sub-task transitioned to in_progress so
    // it can mark the matching SubTaskCard as active (green border, auto-
    // expand body, set currentSubTaskId). Without this signal the UI
    // would still see the sub-task as "pending" until the next terminal
    // transition fires. The first-sight Start carries the initial status
    // (pending); the activation Start carries in_progress.

    @Test
    void pendingToInProgress_emitsStart() {
        setTodos(List.of(sub("a", "pending")));
        engine.emitSubTaskTransitions(sink::add);
        sink.clear();
        // Flip to in_progress.
        setTodos(List.of(sub("a", "in_progress")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("START:a"), eventTypes());
        StreamEvent.SubTaskStart s = (StreamEvent.SubTaskStart) sink.get(0);
        assertEquals("in_progress", s.status());
    }

    // ---- 7. in_progress -> completed (emits End) ----

    @Test
    void inProgressToCompleted_emitsEnd() {
        setTodos(List.of(sub("a", "in_progress")));
        engine.emitSubTaskTransitions(sink::add);
        sink.clear();
        setTodos(List.of(sub("a", "completed", "done")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("END:a:completed"), eventTypes());
        StreamEvent.SubTaskEnd e = (StreamEvent.SubTaskEnd) sink.get(0);
        assertEquals("done", e.summary());
    }

    // ---- 8. completed -> in_progress (re-open: emits Start) ----
    // The model may re-open a previously-completed sub-task (e.g. it
    // thought it was done but found a follow-up). The engine should
    // emit a fresh Start.

    @Test
    void completedToInProgress_reopensWithStart() {
        setTodos(List.of(sub("a", "completed", "v1")));
        engine.emitSubTaskTransitions(sink::add);
        sink.clear();
        setTodos(List.of(sub("a", "in_progress")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("START:a"), eventTypes());
        StreamEvent.SubTaskStart s = (StreamEvent.SubTaskStart) sink.get(0);
        assertEquals("in_progress", s.status());
    }

    // ---- 9. Sub-task removed from list (treated as skipped) ----
    // The model might drop a sub-task from its todo list without
    // formally closing it. The engine treats the disappearance as a
    // "skipped" close.

    @Test
    void subTaskRemovedFromList_emitsSkipped() {
        setTodos(List.of(sub("a", "in_progress"), sub("b", "pending")));
        engine.emitSubTaskTransitions(sink::add);
        sink.clear();
        // Now "b" is gone, "a" is still in_progress.
        setTodos(List.of(sub("a", "in_progress")));
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("END:b:skipped"), eventTypes());
    }

    // ---- 10. Whole todo list cleared (all tracked sub-tasks -> skipped) ----

    @Test
    void todoListCleared_emitsSkippedForAll() {
        setTodos(List.of(sub("a", "in_progress")));
        engine.emitSubTaskTransitions(sink::add);
        sink.clear();
        state.setTodoList(List.of());  // cleared
        engine.emitSubTaskTransitions(sink::add);
        assertEquals(List.of("END:a:skipped"), eventTypes());
    }

    // ---- 11. Multiple sub-tasks across multiple top-level todos ----

    @Test
    void multipleSubTasks_acrossTodos_eachEmitsIndependently() {
        List<Map<String, Object>> todos = new ArrayList<>();
        // Top-level todo 0
        Map<String, Object> t0 = new HashMap<>();
        t0.put("content", "alpha");
        t0.put("status", "in_progress");
        t0.put("subtasks", List.of(sub("x", "in_progress"), sub("y", "pending")));
        todos.add(t0);
        // Top-level todo 1
        Map<String, Object> t1 = new HashMap<>();
        t1.put("content", "beta");
        t1.put("status", "pending");
        t1.put("subtasks", List.of(sub("z", "completed", "z-done")));
        todos.add(t1);
        state.setTodoList(todos);
        engine.emitSubTaskTransitions(sink::add);
        // x: in_progress (start), y: pending (start), z: completed (start + end)
        assertEquals(
            List.of("START:x", "START:y", "START:z", "END:z:completed"),
            eventTypes());
    }

    // ---- 12. Sub-task without id is ignored ----
    // Defensive: a malformed sub-task must not crash the loop.

    @Test
    void subTaskWithoutId_isIgnored() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("content", "no id here");
        bad.put("status", "in_progress");
        // no "id" key
        setTodos(List.of(bad));
        engine.emitSubTaskTransitions(sink::add);
        assertTrue(sink.isEmpty(), "sub-task without id should be skipped, got: " + eventTypes());
    }
}
