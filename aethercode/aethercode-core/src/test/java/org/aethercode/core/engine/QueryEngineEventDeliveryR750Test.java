package org.aethercode.core.engine;

import org.aethercode.core.app.AppState;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R750 behavioural regression tests for {@code QueryEngine.tryAdvance}'s
 * event-delivery contract.
 *
 * <h2>What was broken</h2>
 *
 * <p>{@code tryAdvance} is a {@link Spliterator#tryAdvance} implementation and
 * the contract is: <em>per call, hand the consumer AT MOST ONE event</em>. But
 * pre-R750 the method called {@code action.accept(...)} up to 23 times inside a
 * single invocation — e.g. a tool batch that emits {@code ToolUseStart} ×N,
 * then {@code ToolResult} ×N, then a {@code SideNote}. That silently violated
 * the contract for every consumer that probes before pulling, and the
 * production consumer does exactly that: {@code StreamConsumerGuard.consume()}
 * runs a probe thread calling {@code it.hasNext()} with a throwaway action.
 * Every event that probe's action swallowed was <em>gone</em>. Only the single
 * event that {@code it.next()} happened to return afterwards survived.
 *
 * <p>Observable symptom: tool calls disappeared from the Claude stream-json
 * transcript while tool results still arrived — producing orphan
 * {@code tool_result} frames, which the provider rejects with
 * {@code 400 invalid params, tool result's tool id (call_X) not found (2013)}.
 *
 * <h2>The fix</h2>
 *
 * <p>Internal emits now go through a {@code pendingEvents} buffer
 * ({@code emit()} enqueues, {@code drainOne()} delivers one), and every
 * {@code return} site drains the buffer before returning. The terminal
 * {@code RunEnd} in the outer catch bypasses the buffer and goes straight to
 * {@code action.accept}, because a buffered RunEnd drained behind an older
 * event and then terminated would strand the terminal event.
 *
 * <h2>Why these tests are behavioural, not source-pin</h2>
 *
 * <p>The pre-R750 failure was invisible to source-pin tests: the source
 * plainly contained the {@code action.accept(...ToolUseStart...)} calls. Only
 * actually <em>running</em> the Spliterator through a probe-then-pull consumer
 * reproduces the loss. So these tests drive the real engine and assert on the
 * events that came out.
 */
class QueryEngineEventDeliveryR750Test {

    /**
     * The regression itself: a turn that emits MULTIPLE events in one
     * {@code tryAdvance} must have all of them delivered.
     *
     * <p>The stub client asks for two tool calls in one turn. Pre-R750 that
     * produced 2× {@code ToolUseStart} + 2× {@code ToolResult} + {@code RunEnd}
     * from a single {@code tryAdvance}, of which the probe consumer kept only
     * one — leaving orphans.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void multiEventTurn_deliversEveryToolUseAndResult() {
        ChatClient twoToolCalls = new ChatClient() {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                if (calls.getAndIncrement() == 0) {
                    return Stream.of(
                            new StreamEvent.ToolUseStart("call_A", "read_file", Map.of("path", "a.txt")),
                            new StreamEvent.ToolUseStart("call_B", "read_file", Map.of("path", "b.txt")),
                            new StreamEvent.RunEnd("tool_use", List.of(
                                    new ContentBlock.ToolUseBlock("call_A", "read_file", Map.of("path", "a.txt")),
                                    new ContentBlock.ToolUseBlock("call_B", "read_file", Map.of("path", "b.txt")))));
                }
                return Stream.of(new StreamEvent.RunEnd("end_turn", List.of(
                        new ContentBlock.TextBlock("done"))));
            }

            @Override public String modelId() { return "m"; }
        };

        QueryEngine engine = newEngineWithClient(twoToolCalls, List.of());

        // Drive it the way StreamConsumerGuard does: a probe thread that
        // calls hasNext() with a throwaway action, and a real consumer that
        // uses next(). This is the exact shape that dropped events pre-R750.
        List<StreamEvent> delivered = collectRun(engine.query("go"));

        List<String> toolUses = idsOf(delivered, StreamEvent.ToolUseStart.class);
        List<String> toolResults = idsOf(delivered, StreamEvent.ToolResult.class);

        assertEquals(List.of("call_A", "call_B"), toolUses,
                "R750: every ToolUseStart the engine produced must reach the consumer; "
                        + "delivered events were " + describe(delivered));
        assertEquals(List.of("call_A", "call_B"), toolResults,
                "R750: every ToolResult must reach the consumer; "
                        + "delivered events were " + describe(delivered));

        // The pairing invariant the provider actually enforces.
        for (String id : toolResults) {
            assertTrue(toolUses.contains(id),
                    "R750: orphan tool_result " + id + " — no matching tool_use was "
                            + "delivered. This is the exact shape that produces "
                            + "400 (2013) 'tool result's tool id not found'.");
        }
    }

    /**
     * The complementary direction: a tool_use frame delivered WITHOUT its
     * tool_result would strand a pending call in the provider's view.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void multiEventTurn_pairsEveryToolUseWithAResult() {
        ChatClient twoToolCalls = new ChatClient() {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                if (calls.getAndIncrement() == 0) {
                    return Stream.of(
                            new StreamEvent.ToolUseStart("call_A", "read_file", Map.of("path", "a.txt")),
                            new StreamEvent.ToolUseStart("call_B", "read_file", Map.of("path", "b.txt")),
                            new StreamEvent.RunEnd("tool_use", List.of(
                                    new ContentBlock.ToolUseBlock("call_A", "read_file", Map.of("path", "a.txt")),
                                    new ContentBlock.ToolUseBlock("call_B", "read_file", Map.of("path", "b.txt")))));
                }
                return Stream.of(new StreamEvent.RunEnd("end_turn", List.of(
                        new ContentBlock.TextBlock("done"))));
            }

            @Override public String modelId() { return "m"; }
        };

        QueryEngine engine = newEngineWithClient(twoToolCalls, List.of());
        List<StreamEvent> delivered = collectRun(engine.query("go"));

        for (String id : idsOf(delivered, StreamEvent.ToolUseStart.class)) {
            assertTrue(idsOf(delivered, StreamEvent.ToolResult.class).contains(id),
                    "R750: tool_use " + id + " was delivered with no matching tool_result");
        }
    }

    /**
     * A single tool call still works — the single-event-per-call contract must
     * not have cost us the simple case (this is the shape the pre-R750 tests
     * already covered, kept as a regression guard against over-buffering).
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void singleToolCall_stillDeliversUseAndResult() {
        ChatClient oneToolCall = new ChatClient() {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                if (calls.getAndIncrement() == 0) {
                    return Stream.of(
                            new StreamEvent.ToolUseStart("call_X", "read_file", Map.of("path", "a.txt")),
                            new StreamEvent.RunEnd("tool_use", List.of(
                                    new ContentBlock.ToolUseBlock("call_X", "read_file", Map.of("path", "a.txt")))));
                }
                return Stream.of(new StreamEvent.RunEnd("end_turn", List.of(
                        new ContentBlock.TextBlock("done"))));
            }

            @Override public String modelId() { return "m"; }
        };

        QueryEngine engine = newEngineWithClient(oneToolCall, List.of());
        List<StreamEvent> delivered = collectRun(engine.query("go"));

        assertEquals(List.of("call_X"), idsOf(delivered, StreamEvent.ToolUseStart.class),
                "R750: single tool_use must still be delivered; got " + describe(delivered));
        assertEquals(List.of("call_X"), idsOf(delivered, StreamEvent.ToolResult.class),
                "R750: single tool_result must still be delivered; got " + describe(delivered));
    }

    /**
     * {@code RunStart} is RUN-scoped, not TURN-scoped. Pre-R750 it was emitted
     * from the branch entered on every LLM turn, so a two-turn run produced
     * two {@code system/init} frames. The stream-json contract wants exactly
     * one per run.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void multiTurnRun_emitsExactlyOneRunStart() {
        ChatClient twoTurns = new ChatClient() {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                if (calls.getAndIncrement() == 0) {
                    return Stream.of(
                            new StreamEvent.ToolUseStart("call_1", "read_file", Map.of("path", "a.txt")),
                            new StreamEvent.RunEnd("tool_use", List.of(
                                    new ContentBlock.ToolUseBlock("call_1", "read_file", Map.of("path", "a.txt")))));
                }
                return Stream.of(
                        new StreamEvent.TextDelta("done"),
                        new StreamEvent.RunEnd("end_turn", List.of(
                                new ContentBlock.TextBlock("done"))));
            }

            @Override public String modelId() { return "m"; }
        };

        QueryEngine engine = newEngineWithClient(twoTurns, List.of());
        List<StreamEvent> delivered = collectRun(engine.query("go"));

        long runStarts = delivered.stream().filter(e -> e instanceof StreamEvent.RunStart).count();
        assertEquals(1, runStarts,
                "R750: RunStart is run-scoped — a two-turn run must emit exactly one "
                        + "system/init frame, not one per turn. Delivered: " + describe(delivered));
    }

    /**
     * R750b — the exact symptom this round chased:
     * {@code no_run_end: engine stream ended without RunEnd}.
     *
     * <p>A tool-using run has at least two turns: turn 1 declares the tool
     * call, the batch executes, turn 2 carries the model's answer. Turn 2's
     * start-of-turn branch produces NO consumer-visible event (no RunStart —
     * that is run-scoped — and the LLM call itself is not an event), and it
     * used to end with a bare {@code return true;}.
     *
     * <p>That is the Spliterator contract's other half, and R750 only fixed
     * the first half: {@code tryAdvance} must not return {@code true} after
     * handing the action MORE than one event. Returning {@code true} after
     * handing it NONE desynchronises
     * {@code Spliterators.AbstractSpliterator} identically — the iterator
     * caches "there is an element", {@code next()} finds nothing to return,
     * and the next {@code hasNext()} re-enters {@code tryAdvance} with an
     * empty buffer and reports exhaustion. A probe-then-pull consumer reads
     * that as a clean drain and exits before the terminal RunEnd.
     *
     * <p>The observable proof: turn 2's events must actually arrive, and the
     * run must still end on a RunEnd.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void toolUsingRun_reachesTurnTwoAndStillTerminates() {
        ChatClient twoTurns = new ChatClient() {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                if (calls.getAndIncrement() == 0) {
                    return Stream.of(
                            new StreamEvent.ToolUseStart("call_1", "read_file", Map.of("path", "a.txt")),
                            new StreamEvent.RunEnd("tool_use", List.of(
                                    new ContentBlock.ToolUseBlock("call_1", "read_file", Map.of("path", "a.txt")))));
                }
                return Stream.of(
                        new StreamEvent.TextDelta("the answer"),
                        new StreamEvent.RunEnd("end_turn", List.of(
                                new ContentBlock.TextBlock("the answer"))));
            }

            @Override public String modelId() { return "m"; }
        };

        QueryEngine engine = newEngineWithClient(twoTurns, List.of());
        List<StreamEvent> delivered = collectRun(engine.query("go"));

        // Turn 2's TextDelta is the canary. Pre-R750b the run ended before
        // it was ever delivered and the consumer saw no terminal event.
        assertTrue(idsOf(delivered, StreamEvent.ToolUseStart.class).contains("call_1"),
                "R750b: turn 1's tool_use must be delivered; got " + describe(delivered));
        assertTrue(delivered.stream().anyMatch(e -> e instanceof StreamEvent.TextDelta t
                        && "the answer".equals(t.text())),
                "R750b: turn 2 must actually stream — the run was ending at the "
                        + "start-of-turn boundary instead of continuing to the model. "
                        + "This is the `no_run_end` symptom. Delivered: " + describe(delivered));
        assertTrue(delivered.get(delivered.size() - 1) instanceof StreamEvent.RunEnd,
                "R750b: a tool-using run must still terminate with a RunEnd; got "
                        + describe(delivered));
    }

    /**
     * The run MUST terminate with a {@code RunEnd}. This is the R735a/R744
     * contract, re-pinned behaviourally because the R750 buffering change
     * created a way to violate it (buffer the RunEnd, drain an older event,
     * then {@code return false}).
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void everyRun_terminatesWithExactlyOneRunEnd() {
        ChatClient simple = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                return Stream.of(
                        new StreamEvent.TextDelta("hello"),
                        new StreamEvent.RunEnd("stop",
                                List.of(new ContentBlock.TextBlock("hello"))));
            }
            @Override public String modelId() { return "m"; }
        };

        QueryEngine engine = newEngineWithClient(simple, List.of());
        List<StreamEvent> delivered = collectRun(engine.query("hi"));

        assertFalse(delivered.isEmpty(), "R750: no events delivered at all");
        assertTrue(delivered.get(delivered.size() - 1) instanceof StreamEvent.RunEnd,
                "R750: the LAST delivered event must be the terminal RunEnd — otherwise the "
                        + "renderer never leaves its streaming state and sits in the green "
                        + "● Idle with no error banner. Delivered: " + describe(delivered));

        long runEnds = delivered.stream().filter(e -> e instanceof StreamEvent.RunEnd).count();
        assertEquals(1, runEnds, "R750: exactly one terminal RunEnd per run; got " + describe(delivered));
    }

    /**
     * When the LLM stream throws mid-iteration, the outer catch must still
     * produce an observable terminal {@code RunEnd} — and it must be the LAST
     * event, not stranded behind a buffered non-terminal event.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void throwingLlmStream_stillTerminatesWithRunEnd() {
        ChatClient boom = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                return Stream.concat(
                        Stream.of(new StreamEvent.ToolUseStart("call_Z", "read_file", Map.of("path", "z.txt"))),
                        Stream.generate(() -> {
                            throw new IllegalStateException("upstream 500");
                        }));
            }
            @Override public String modelId() { return "m"; }
        };

        QueryEngine engine = newEngineWithClient(boom, List.of());
        List<StreamEvent> delivered = collectRun(engine.query("go"));

        assertFalse(delivered.isEmpty(), "R750: a throwing stream must still deliver its terminal RunEnd");
        assertTrue(delivered.get(delivered.size() - 1) instanceof StreamEvent.RunEnd,
                "R750: the outer catch's RunEnd must be the LAST delivered event, not stranded "
                        + "behind a buffered ToolUseStart. Delivered: " + describe(delivered));

        StreamEvent.RunEnd end = (StreamEvent.RunEnd) delivered.get(delivered.size() - 1);
        assertTrue(end.stopReason().startsWith("runtime_exception"),
                "R750: the outer catch must classify the throw as runtime_exception so the "
                        + "desktop shows the ● Failed (reason) pill; got: " + end.stopReason());
    }

    // ----- helpers -----

    /**
     * Collect a run using the EXACT loop shape that
     * {@code StreamConsumerGuard.consume()} uses in production:
     * <pre>{@code while (it.hasNext()) { out.add(it.next()); } }</pre>
     *
     * <p>This matters. {@code Iterator.hasNext()} on a spliterator-backed
     * iterator internally runs {@code tryAdvance} to advance — passing
     * whatever action the iterator holds internally (typically a
     * throwaway). Pre-R750, {@code tryAdvance} itself called
     * {@code action.accept(...)} up to 23 times in one invocation, so a
     * busy turn's {@code ToolUseStart} / {@code ToolResult} / {@code SideNote}
     * events were delivered to that internal throwaway and vanished from
     * {@code it.next()}'s point of view. R750 buffers them and hands over
     * exactly one per call, so {@code it.next()} sees every one.
     *
     * <p>Using {@code forEach} here would NOT catch the regression —
     * {@code forEach} passes a real action to {@code tryAdvance}, so
     * over-emission is invisible to it. The {@code hasNext/next} loop is
     * the discriminating shape.
     */
    private static List<StreamEvent> collectRun(Stream<StreamEvent> stream) {
        List<StreamEvent> out = new ArrayList<>();
        java.util.Iterator<StreamEvent> it = stream.iterator();
        while (it.hasNext()) {
            StreamEvent e = it.next();
            out.add(e);
            // Stop at the terminal event, like the guard does.
            if (e instanceof StreamEvent.RunEnd) break;
        }
        return out;
    }

    private static List<String> idsOf(List<StreamEvent> events, Class<?> type) {
        List<String> ids = new ArrayList<>();
        for (StreamEvent e : events) {
            if (type.isInstance(e)) {
                if (e instanceof StreamEvent.ToolUseStart t) ids.add(t.id());
                else if (e instanceof StreamEvent.ToolResult r) ids.add(r.id());
            }
        }
        return ids;
    }

    private static String describe(List<StreamEvent> events) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) sb.append(", ");
            StreamEvent e = events.get(i);
            if (e instanceof StreamEvent.ToolUseStart t) sb.append("ToolUseStart(").append(t.id()).append(")");
            else if (e instanceof StreamEvent.ToolResult r) sb.append("ToolResult(").append(r.id()).append(")");
            else sb.append(e.getClass().getSimpleName());
        }
        return sb.append("]").toString();
    }

    private static QueryEngine newEngineWithClient(ChatClient client, List<Tool> tools) {
        AppState state = new AppState("s1", Path.of(""));
        state.toolPool().addAll(tools);
        PermissionPolicy allowAll =
                (tool, input, ctx) -> CompletableFuture.completedFuture(
                        new org.aethercode.core.permission.PermissionResult.Allow(Map.of()));
        StreamingToolExecutor noopExec = new StreamingToolExecutor(allowAll, 1) {
            @Override
            public Stream<Event> run(List<ContentBlock.ToolUseBlock> batch, AppState appState) {
                List<Event> events = new ArrayList<>();
                for (ContentBlock.ToolUseBlock tu : batch) {
                    events.add(new Event.Started(tu.id(), tu.name(), tu.input()));
                    appState.appendMessage(Message.toolResult(tu.id(), "ok", false));
                    events.add(new Event.Completed(tu.id(), "ok", false));
                }
                events.add(new Event.BatchEnd());
                return events.stream();
            }
        };
        return new QueryEngine(state, client, allowAll, "", null, noopExec, null, null);
    }
}