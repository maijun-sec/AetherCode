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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R416 — the turn budget must be REAL.
 *
 * <h2>★ WHAT WAS BROKEN ★</h2>
 * <p>{@code maxTurnsPerQuery} was plumbed to the CLI ({@code --max-turns}),
 * the SDK builder and the wire, and the CLI's help text told the user
 * <b>"Cap on model turns per query (default 50)"</b> — while the engine
 * <b>never read the value</b>. R83 removed the per-query turn cap (the
 * todo controller took over) and left the knob behind: the promise
 * stayed, the enforcement did not.
 *
 * <h2>★ THE CONTRACT NOW ★</h2>
 * <ul>
 *   <li>the default is UNBOUNDED (the engine always said -1; the CLI's
 *       50 was a fossil that contradicted it) — a long legitimate round
 *       must not be cut off;</li>
 *   <li>when a cap IS set, the engine ENFORCES it: the turn ends with
 *       {@code max_iterations}, the daemon reports it as a failure (the
 *       work is unfinished), and the model is told to wrap up in the
 *       last fifth of the budget.</li>
 * </ul>
 */
class QueryEngineTurnBudgetR416Test {

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R416: a turn that reaches its budget ends with max_iterations")
    void aBudgetStopsTheTurn() {
        Tool echo = Tools.build(new ToolDef("echo", "echo input",
                Map.of("type", "object", "properties", Map.of("msg", Map.of("type", "string"))),
                (input, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));

        AtomicInteger llmCalls = new AtomicInteger();
        ChatClient neverEnds = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                int n = llmCalls.incrementAndGet();
                ContentBlock.ToolUseBlock tu = new ContentBlock.ToolUseBlock(
                        "call-" + n, "echo", Map.of("msg", "tick-" + n));
                return Stream.of(
                        new StreamEvent.RunStart("run-" + n, "test-model"),
                        new StreamEvent.ToolUseStart(tu.id(), tu.name(), tu.input()),
                        new StreamEvent.RunEnd("tool_use", List.of(tu)));
            }
            @Override public String modelId() { return "test-model"; }
        };

        QueryEngine engine = newEngineWithClient(neverEnds, List.of(echo));
        engine.setMaxTurnsPerQuery(3);

        List<StreamEvent> events = new ArrayList<>();
        engine.query("keep going").forEach(events::add);

        StreamEvent.RunEnd end = (StreamEvent.RunEnd) events.get(events.size() - 1);
        assertTrue(end.stopReason().startsWith("max_iterations"),
                "the turn must end with the budget's stop reason, got: " + end.stopReason());
        assertTrue(end.stopReason().contains("3"),
                "the stop reason must name the budget, got: " + end.stopReason());
        assertTrue(end.isErrorOutcome(),
                "the run ended without finishing the user's request — that is a failure verdict");
        assertTrue(end.isDeterministicHardStop(),
                "the engine must not continue by itself after the budget is gone");
        assertEquals(3, llmCalls.get(),
                "the budget must be enforced EXACTLY: three model turns, no more");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R416: the model is told to wrap up before the budget runs out")
    void theModelIsToldToWrapUp() {
        List<String> systemPrompts = new ArrayList<>();
        Tool echo = Tools.build(new ToolDef("echo", "echo input",
                Map.of("type", "object", "properties", Map.of("msg", Map.of("type", "string"))),
                (input, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));

        AtomicInteger llmCalls = new AtomicInteger();
        ChatClient neverEnds = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                int n = llmCalls.incrementAndGet();
                systemPrompts.add(systemPrompt);
                ContentBlock.ToolUseBlock tu = new ContentBlock.ToolUseBlock(
                        "call-" + n, "echo", Map.of("msg", "tick-" + n));
                return Stream.of(
                        new StreamEvent.RunStart("run-" + n, "test-model"),
                        new StreamEvent.ToolUseStart(tu.id(), tu.name(), tu.input()),
                        new StreamEvent.RunEnd("tool_use", List.of(tu)));
            }
            @Override public String modelId() { return "test-model"; }
        };

        QueryEngine engine = newEngineWithClient(neverEnds, List.of(echo));
        engine.setMaxTurnsPerQuery(5);

        engine.query("keep going").forEach(ev -> { });

        assertTrue(systemPrompts.size() >= 2, "the engine must have issued several turns");
        String last = systemPrompts.get(systemPrompts.size() - 1);
        assertTrue(last.contains("Turn budget"),
                "the last turn's prompt must carry the budget directive, got: " + last);
        assertTrue(last.contains("model turns"),
                "the directive must say what the budget is, got: " + last);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R416: with no budget the turn is not cut off")
    void noBudgetMeansNoCut() {
        Tool echo = Tools.build(new ToolDef("echo", "echo input",
                Map.of("type", "object", "properties", Map.of("msg", Map.of("type", "string"))),
                (input, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));

        AtomicInteger llmCalls = new AtomicInteger();
        ChatClient eightTurns = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                int n = llmCalls.incrementAndGet();
                if (n <= 8) {
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

        QueryEngine engine = newEngineWithClient(eightTurns, List.of(echo));
        assertEquals(-1, engine.maxTurnsPerQuery(),
                "the engine's default must stay unbounded — a legitimate long round must not be cut off");

        List<StreamEvent> events = new ArrayList<>();
        engine.query("keep going").forEach(events::add);

        StreamEvent.RunEnd end = (StreamEvent.RunEnd) events.get(events.size() - 1);
        assertEquals("end_turn", end.stopReason(),
                "the run must reach the model's own ending, got: " + end.stopReason());
        assertFalse(end.stopReason().startsWith("max_iterations"), "no budget, no budget stop");
        assertEquals(9, llmCalls.get(), "every turn must have been served");
    }

    // ----- helpers -----

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
                            // The body VARIES with the call: this test is
                            // about the budget, not about the stall. (A
                            // constant body with varying calls is the
                            // stall's shape — R416's other pattern.)
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
