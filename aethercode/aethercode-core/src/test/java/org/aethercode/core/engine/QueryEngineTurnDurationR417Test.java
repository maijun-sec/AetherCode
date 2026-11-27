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
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R417 — a turn must END, even when its calls are all different.
 *
 * <h2>★ WHAT WAS BROKEN ★</h2>
 * <p>R416's live matrix measured two scenarios whose work was <b>done</b> and
 * whose turn kept running anyway:
 * <ul>
 *   <li><b>D4</b> — the deliverable landed 5m21s into the turn, and the turn
 *       then ran for another 9m30s (104 model calls, every one of them
 *       different);</li>
 *   <li><b>K1</b> — the answer had been given, and the turn kept fetching
 *       (142 calls, every one of them different).</li>
 * </ul>
 * <p>⇒ The turn-COUNT budget cannot catch that (the calls were all
 * different), and the stall detector cannot either: it measures whether a
 * tool RESULT was already seen, and a rabbit hole keeps producing results it
 * has not seen.
 *
 * <h2>★ THE CONTRACT NOW ★</h2>
 * <ul>
 *   <li>a turn has a wall-clock budget ({@code maxTurnMillis}), on by
 *       default — ten minutes, a value MEASURED from R416's matrix: 23
 *       scenarios finished within about three minutes, the two runaway ones
 *       were still running at fifteen;</li>
 *   <li>when the budget is reached the turn ends with {@code max_duration},
 *       which is a FAILURE verdict (the work is unfinished on purpose) and a
 *       deterministic hard stop (the engine does not continue by itself);</li>
 *   <li>in the last fifth of the budget the model is told how much time is
 *       left, so it can FINISH instead of being cut off mid-step;</li>
 *   <li>0 (or negative) means unbounded — the same escape hatch the
 *       turn-count budget has.</li>
 * </ul>
 */
class QueryEngineTurnDurationR417Test {

    /**
     * ★ A CLOCK THE TEST ADVANCES BY HAND ★ a budget that can only be
     * tested by sleeping is a budget nobody tests — and a test that sleeps
     * for ten minutes is a test the suite stops running.
     */
    private static final class TestClock implements LongSupplier {
        private long nanos = 0L;
        @Override public long getAsLong() { return nanos; }
        void advanceMillis(long ms) { nanos += ms * 1_000_000L; }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R417: a turn that runs out of time ends with max_duration")
    void aTurnThatRunsOutOfTimeEndsWithMaxDuration() {
        TestClock clock = new TestClock();
        AtomicInteger llmCalls = new AtomicInteger();
        ChatClient neverEnds = tickingClient(clock, llmCalls, 4 * 60_000);

        QueryEngine engine = newEngineWithClient(neverEnds, List.of(echoTool()));
        engine.setTurnClock(clock);
        engine.setMaxTurnMillis(600_000L);

        List<StreamEvent> events = new ArrayList<>();
        engine.query("keep going").forEach(events::add);

        StreamEvent.RunEnd end = (StreamEvent.RunEnd) events.get(events.size() - 1);
        assertTrue(end.stopReason().startsWith("max_duration"),
                "the turn must end with the time budget's stop reason, got: " + end.stopReason());
        assertTrue(end.stopReason().contains("600"),
                "the stop reason must name the budget, got: " + end.stopReason());
        assertTrue(end.isErrorOutcome(),
                "the run ended without finishing the user's request — that is a failure verdict");
        assertTrue(end.isDeterministicHardStop(),
                "the engine must not continue by itself after the time budget is gone");
        // ★ THE NOTE COMES BEFORE THE RunEnd ★ a client that renders the
        // verdict needs it in the stream, not after the run closed.
        int noteAt = indexOfSideNote(events, "max_duration");
        int endAt = events.size() - 1;
        assertTrue(noteAt >= 0, "the turn must explain itself: no max_duration side note");
        assertTrue(noteAt < endAt, "the verdict must be delivered BEFORE the terminal event");
        // ★ THE BUDGET IS SPENT EXACTLY ★ three four-minute turns fit into
        // a ten-minute budget; the fourth is refused before the model call.
        assertEquals(3, llmCalls.get(),
                "the budget must be enforced EXACTLY: three model turns, no more");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R417: the model is told how much time is left before the budget runs out")
    void theModelIsToldHowMuchTimeIsLeft() {
        TestClock clock = new TestClock();
        List<String> systemPrompts = new ArrayList<>();
        AtomicInteger llmCalls = new AtomicInteger();

        ChatClient neverEnds = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                systemPrompts.add(systemPrompt);
                int n = llmCalls.incrementAndGet();
                clock.advanceMillis(4 * 60_000);
                ContentBlock.ToolUseBlock tu = new ContentBlock.ToolUseBlock(
                        "call-" + n, "echo", Map.of("msg", "tick-" + n));
                return Stream.of(
                        new StreamEvent.RunStart("run-" + n, "test-model"),
                        new StreamEvent.ToolUseStart(tu.id(), tu.name(), tu.input()),
                        new StreamEvent.RunEnd("tool_use", List.of(tu)));
            }
            @Override public String modelId() { return "test-model"; }
        };

        QueryEngine engine = newEngineWithClient(neverEnds, List.of(echoTool()));
        engine.setTurnClock(clock);
        engine.setMaxTurnMillis(600_000L);

        engine.query("keep going").forEach(ev -> { });

        assertTrue(systemPrompts.size() >= 2, "the engine must have issued several turns");
        String last = systemPrompts.get(systemPrompts.size() - 1);
        assertTrue(last.contains("Turn time budget"),
                "the last turn's prompt must carry the time directive, got: " + last);
        assertTrue(last.contains("seconds"),
                "the directive must say what the budget is, got: " + last);
        // ★ THE COUNTDOWN IS ABOUT TIME, NOT ABOUT CALLS ★ a model told
        // "you have N calls left" spends them however long that takes.
        assertTrue(last.contains("WALL CLOCK") || last.contains("wall-clock"),
                "the directive must name the axis it counts down, got: " + last);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R417: with no time budget the turn is not cut off")
    void noTimeBudgetMeansNoCut() {
        TestClock clock = new TestClock();
        AtomicInteger llmCalls = new AtomicInteger();
        ChatClient fourTurns = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                int n = llmCalls.incrementAndGet();
                clock.advanceMillis(60_000);
                if (n <= 4) {
                    ContentBlock.ToolUseBlock tu = new ContentBlock.ToolUseBlock(
                            "call-" + n, "echo", Map.of("msg", "tick-" + n));
                    return Stream.of(
                            new StreamEvent.RunStart("run-" + n, "test-model"),
                            new StreamEvent.ToolUseStart(tu.id(), tu.name(), tu.input()),
                            new StreamEvent.RunEnd("tool_use", List.of(tu)));
                }
                return Stream.of(
                        new StreamEvent.RunStart("run-end", "test-model"),
                        new StreamEvent.TextDelta("done"),
                        new StreamEvent.RunEnd("end_turn", List.of()));
            }
            @Override public String modelId() { return "test-model"; }
        };

        QueryEngine engine = newEngineWithClient(fourTurns, List.of(echoTool()));
        engine.setTurnClock(clock);
        engine.setMaxTurnMillis(0L);

        List<StreamEvent> events = new ArrayList<>();
        engine.query("keep going").forEach(events::add);

        StreamEvent.RunEnd end = (StreamEvent.RunEnd) events.get(events.size() - 1);
        assertEquals("end_turn", end.stopReason(),
                "the run must reach the model's own ending, got: " + end.stopReason());
        assertFalse(end.stopReason().startsWith("max_duration"), "no budget, no budget stop");
        assertEquals(5, llmCalls.get(), "every turn must have been served");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R417: the default time budget is ten minutes, and it is a value the user can change")
    void theDefaultTimeBudgetIsTenMinutes() {
        QueryEngine engine = newEngineWithClient(
                new ChatClient() {
                    @Override
                    public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                        return Stream.of(new StreamEvent.RunEnd("end_turn", List.of()));
                    }
                    @Override public String modelId() { return "test-model"; }
                }, List.of(echoTool()));

        assertEquals(600_000L, engine.maxTurnMillis(),
                "the default must be the MEASURED gap: above every healthy turn, below every runaway one");
        assertEquals(QueryEngine.DEFAULT_MAX_TURN_MILLIS, engine.maxTurnMillis());

        engine.setMaxTurnMillis(1_200_000L);
        assertEquals(1_200_000L, engine.maxTurnMillis(),
                "the budget must be settable — a bound nobody can raise is a bound nobody can live with");
        engine.setMaxTurnMillis(0L);
        assertEquals(0L, engine.maxTurnMillis(), "0 must mean unbounded");
    }

    // ----- helpers -----

    private static ChatClient tickingClient(TestClock clock, AtomicInteger llmCalls, long perTurnMillis) {
        return new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                int n = llmCalls.incrementAndGet();
                clock.advanceMillis(perTurnMillis);
                ContentBlock.ToolUseBlock tu = new ContentBlock.ToolUseBlock(
                        "call-" + n, "echo", Map.of("msg", "tick-" + n));
                return Stream.of(
                        new StreamEvent.RunStart("run-" + n, "test-model"),
                        new StreamEvent.ToolUseStart(tu.id(), tu.name(), tu.input()),
                        new StreamEvent.RunEnd("tool_use", List.of(tu)));
            }
            @Override public String modelId() { return "test-model"; }
        };
    }

    private static int indexOfSideNote(List<StreamEvent> events, String kind) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof StreamEvent.SideNote sn && kind.equals(sn.kind())) return i;
        }
        return -1;
    }

    private static Tool echoTool() {
        return Tools.build(new ToolDef("echo", "echo input",
                Map.of("type", "object", "properties", Map.of("msg", Map.of("type", "string"))),
                (input, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));
    }

    private static QueryEngine newEngineWithClient(ChatClient client, List<Tool> tools) {
        AppState state = new AppState("s1", Path.of(""));
        state.toolPool().addAll(tools);
        org.aethercode.core.engine.PermissionPolicy allowAll =
                (tool, input, ctx) -> CompletableFuture.completedFuture(
                        new org.aethercode.core.permission.PermissionResult.Allow(Map.of()));
        org.aethercode.core.engine.StreamingToolExecutor exec =
                new org.aethercode.core.engine.StreamingToolExecutor(allowAll, 1) {
                    @Override
                    public java.util.stream.Stream<Event> run(
                            List<ContentBlock.ToolUseBlock> batch, AppState appState) {
                        List<Event> events = new ArrayList<>();
                        for (ContentBlock.ToolUseBlock tu : batch) {
                            events.add(new Event.Started(tu.id(), tu.name(), tu.input()));
                            String body = "tick-" + tu.input().get("msg");
                            appState.appendMessage(Message.toolResult(tu.id(), body, false));
                            events.add(new Event.Completed(tu.id(), body, false));
                        }
                        events.add(new Event.BatchEnd());
                        return events.stream();
                    }
                };
        return new QueryEngine(state, client, allowAll, "", null, exec, null, null);
    }
}
