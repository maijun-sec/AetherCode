package org.aethercode.core.engine;

import org.aethercode.core.app.AppState;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * prior round (revisited): for simple conversational queries (e.g. "how are you?")
 * that don't require tools, the engine must finish after exactly ONE model call.
 * No looping, no re-query. This test pins that behaviour so a future refactor
 * doesn't accidentally re-introduce the "infinite repeat" bug.
 */
class QueryEngineSimpleChatTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void simpleGreetingFinishesAfterOneTurn() {
        AtomicInteger llmCalls = new AtomicInteger();
        ChatClient oneShot = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                llmCalls.incrementAndGet();
                return Stream.of(
                        new StreamEvent.RunStart("r1", "MiniMax-M3"),
                        new StreamEvent.TextDelta("I'm doing well, thanks!"),
                        new StreamEvent.RunEnd("stop",
                                List.of(new ContentBlock.TextBlock("I'm doing well, thanks!")))
                );
            }
            @Override public String modelId() { return "MiniMax-M3"; }
        };
        QueryEngine engine = newEngineWithClient(oneShot, List.of());

        List<StreamEvent> events = new ArrayList<>();
        engine.query("how are you?").forEach(events::add);

        // THE core assertion: only ONE model call.
        assertEquals(1, llmCalls.get(), "simple chat must not loop the LLM, got " + events.size() + " events");
        // The stream ends with a single RunEnd carrying the model's stop reason.
        StreamEvent.RunEnd end = (StreamEvent.RunEnd) events.get(events.size() - 1);
        assertEquals("stop", end.stopReason());
        // Event count: 1 RunStart + 1 TextDelta + 1 engine-side RunEnd = 3.
        // The model-side RunEnd is consumed internally (sets stopReason / finalBlocks)
        // but is NOT re-emitted to the consumer — the engine's terminal RunEnd carries
        // the same info and is the canonical "turn done" signal.
        assertEquals(3, events.size(), "expected exactly 3 events, got: " + events.size());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void emptyToolCallRunEndStillFinishesAfterOneTurn() {
        // Some models send a RunEnd with no finalBlocks (e.g. content filter, refusal).
        // The engine should still finish after one turn.
        AtomicInteger llmCalls = new AtomicInteger();
        ChatClient refuseAll = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                llmCalls.incrementAndGet();
                return Stream.of(
                        new StreamEvent.RunStart("r1", "m"),
                        new StreamEvent.RunEnd("content_filter", List.of())
                );
            }
            @Override public String modelId() { return "m"; }
        };
        QueryEngine engine = newEngineWithClient(refuseAll, List.of());
        List<StreamEvent> events = new ArrayList<>();
        engine.query("test").forEach(events::add);
        assertEquals(1, llmCalls.get());
        assertTrue(events.get(events.size() - 1) instanceof StreamEvent.RunEnd);
    }

    // ----- helpers (same pattern as QueryEngineMaxTurnsTest) -----

    private static QueryEngine newEngineWithClient(ChatClient client, List<Tool> tools) {
        AppState state = new AppState("s1", Path.of(""));
        state.toolPool().addAll(tools);
        org.aethercode.core.engine.PermissionPolicy allowAll =
                (tool, input, ctx) -> CompletableFuture.completedFuture(
                        new org.aethercode.core.permission.PermissionResult.Allow(Map.of()));
        org.aethercode.core.engine.StreamingToolExecutor noopExec =
                new org.aethercode.core.engine.StreamingToolExecutor(allowAll, 1) {
                    @Override
                    public java.util.stream.Stream<Event> run(
                            List<ContentBlock.ToolUseBlock> batch, AppState appState) {
                        List<Event> events = new ArrayList<>();
                        for (ContentBlock.ToolUseBlock tu : batch) {
                            events.add(new Event.Started(tu.id(), tu.name(), tu.input()));
                            Message toolMsg = Message.toolResult(tu.id(), "ok", false);
                            appState.appendMessage(toolMsg);
                            events.add(new Event.Completed(tu.id(), "ok", false));
                        }
                        events.add(new Event.BatchEnd());
                        return events.stream();
                    }
                };
        return new QueryEngine(state, client, allowAll, "", null, noopExec, null, null);
    }
}
