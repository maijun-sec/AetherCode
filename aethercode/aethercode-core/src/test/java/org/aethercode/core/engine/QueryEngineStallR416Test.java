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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R416 — the ENGINE must end a turn that stopped producing new
 * information.
 *
 * <h2>★ WHAT THIS IS ABOUT ★</h2>
 * <p>The turn cap was removed in R83 and {@code TodoRunController} took
 * its place — but the todo controller bounds a turn that HAS a todo list.
 * A research turn has none, so nothing bounded it: R415's live matrix
 * caught a turn that fetched the same document from twenty mirrors and
 * kept going until the user stopped it.
 *
 * <p>This test drives the engine with exactly that shape — every turn a
 * DIFFERENT call, every result the SAME document — and asserts the run
 * ends with an honest stop reason instead of running forever.
 *
 * <h2>★ WHY THE STOP REASON IS loop_detected ★</h2>
 * <p>Because it IS a loop, and {@code loop_detected} is already a failure
 * kind and a deterministic hard stop in the engine's taxonomy. The
 * specific cause travels inside the decoration, which is where a
 * detector's kind belongs (R385). A new bare kind would have needed a
 * new word on the wire, in the TUI and in the desktop — for no gain.
 */
class QueryEngineStallR416Test {

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R416: a turn that keeps re-reading the same answer is ended, not left running")
    void aStallEndsTheRun() {
        Tool fetch = Tools.build(new ToolDef("fetch", "fetch a mirror of a document",
                Map.of("type", "object", "properties", Map.of("url", Map.of("type", "string"))),
                (input, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("THE SAME DOCUMENT"))));

        AtomicInteger llmCalls = new AtomicInteger();
        ChatClient mirror = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                int n = llmCalls.incrementAndGet();
                // A DIFFERENT call every turn (a different mirror) …
                ContentBlock.ToolUseBlock tu = new ContentBlock.ToolUseBlock(
                        "call-" + n, "fetch", Map.of("url", "https://mirror-" + n + ".example/doc"));
                return Stream.of(
                        new StreamEvent.RunStart("run-" + n, "test-model"),
                        new StreamEvent.ToolUseStart(tu.id(), tu.name(), tu.input()),
                        new StreamEvent.RunEnd("tool_use", List.of(tu)));
            }
            @Override public String modelId() { return "test-model"; }
        };

        QueryEngine engine = newEngineWithClient(mirror, List.of(fetch));

        List<StreamEvent> events = new ArrayList<>();
        engine.query("find the spec for this project").forEach(events::add);

        StreamEvent.RunEnd end = (StreamEvent.RunEnd) events.get(events.size() - 1);
        assertTrue(end.stopReason().startsWith("loop_detected: no_new_information"),
                "the run must end with the stall's stop reason, got: " + end.stopReason());
        assertTrue(end.isErrorOutcome(),
                "a turn that was stopped before it finished IS a failure outcome — the daemon must say so");
        assertTrue(end.isDeterministicHardStop(),
                "the engine must not resume by itself after a stall");

        boolean sawTheNote = events.stream().anyMatch(ev ->
                ev instanceof StreamEvent.SideNote sn
                        && "no_new_information_hard_stop".equals(sn.kind()));
        assertTrue(sawTheNote,
                "the user must be told WHY the run ended, not just that it ended: " + events);

        // The whole point: the run ENDED instead of running forever.
        assertTrue(llmCalls.get() <= 12,
                "the engine must stop the runaway; it made " + llmCalls.get() + " model calls");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R416: a turn that keeps learning is NOT stopped")
    void aLearningTurnRunsToTheEnd() {
        Tool read = Tools.build(new ToolDef("read", "read a file",
                Map.of("type", "object", "properties", Map.of("file", Map.of("type", "string"))),
                (input, ctx) -> CompletableFuture.completedFuture(
                        Tool.ToolResult.of("contents of " + input.get("file")))));

        AtomicInteger llmCalls = new AtomicInteger();
        ChatClient learner = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                int n = llmCalls.incrementAndGet();
                if (n <= 10) {
                    ContentBlock.ToolUseBlock tu = new ContentBlock.ToolUseBlock(
                            "call-" + n, "read", Map.of("file", "src/File" + n + ".java"));
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

        QueryEngine engine = newEngineWithClient(learner, List.of(read));

        List<StreamEvent> events = new ArrayList<>();
        engine.query("read the project").forEach(events::add);

        StreamEvent.RunEnd end = (StreamEvent.RunEnd) events.get(events.size() - 1);
        assertEquals("end_turn", end.stopReason(),
                "a turn that keeps learning must reach the model's own ending, got: " + end.stopReason());
        assertEquals(11, llmCalls.get(),
                "the engine must not cut a learning turn short");
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
                            // The tool's REAL result — the constant body is
                            // what makes every turn carry the same answer.
                            String body = "THE SAME DOCUMENT";
                            if ("read".equals(tu.name())) {
                                body = "contents of " + tu.input().get("file");
                            }
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
