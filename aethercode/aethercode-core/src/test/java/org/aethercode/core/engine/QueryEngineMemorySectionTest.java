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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the engine's per-query memory section is appended to the
 * system prompt that the chat client sees. Without this test, a future
 * refactor could drop the section silently and the agent would lose
 * the recalled memory context.
 */
class QueryEngineMemorySectionTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void memorySectionIsAppendedToSystemPrompt() {
        AtomicReference<String> seenSystem = new AtomicReference<>();
        ChatClient capture = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                seenSystem.set(systemPrompt);
                return Stream.of(
                        new StreamEvent.RunStart("r1", "MiniMax-M3"),
                        new StreamEvent.TextDelta("ok"),
                        new StreamEvent.RunEnd("stop",
                                List.of(new ContentBlock.TextBlock("ok")))
                );
            }
            @Override public String modelId() { return "MiniMax-M3"; }
        };
        QueryEngine engine = newEngineWithClient(capture, List.of());
        engine.setMemorySection("# Relevant memories (auto-recalled)\n\n## note.md\n\nbuild commands here\n");

        engine.query("hi").forEach(e -> {});

        String s = seenSystem.get();
        assertTrue(s != null && s.contains("Relevant memories"),
                "system prompt should contain the memory section, was: " + s);
        assertTrue(s.contains("build commands here"),
                "system prompt should contain the memory content, was: " + s);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void memorySectionClearedAfterQuery() {
        // After one query consumes the memory section, the next query
        // should NOT see the old section (the engine resets it in
        // query() before delegating). Without this, two consecutive
        // queries would both re-inject the same memory.
        AtomicReference<String> seenSystem = new AtomicReference<>();
        ChatClient capture = new ChatClient() {
            int calls = 0;
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                seenSystem.set(systemPrompt);
                calls++;
                return Stream.of(
                        new StreamEvent.RunStart("r1", "m"),
                        new StreamEvent.TextDelta("ok"),
                        new StreamEvent.RunEnd("stop",
                                List.of(new ContentBlock.TextBlock("ok")))
                );
            }
            @Override public String modelId() { return "m"; }
        };
        QueryEngine engine = newEngineWithClient(capture, List.of());
        engine.setMemorySection("# memory one\n");
        engine.query("hi").forEach(e -> {});
        assertTrue(seenSystem.get().contains("memory one"));
        // Reset the field and run again.
        engine.setMemorySection("");
        engine.query("hi again").forEach(e -> {});
        assertTrue(!seenSystem.get().contains("memory one"),
                "second query should not contain first query's memory, was: " + seenSystem.get());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void planModeAndMemoryBothApplied() {
        // If both planModeSuffix and memorySection are set, the
        // effective system prompt should contain both. Plan mode wins
        // ordering — memory first, then plan suffix (most recent
        // instruction closest to the model's "now" attention).
        AtomicReference<String> seenSystem = new AtomicReference<>();
        ChatClient capture = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                seenSystem.set(systemPrompt);
                return Stream.of(
                        new StreamEvent.RunStart("r1", "m"),
                        new StreamEvent.TextDelta("ok"),
                        new StreamEvent.RunEnd("stop",
                                List.of(new ContentBlock.TextBlock("ok")))
                );
            }
            @Override public String modelId() { return "m"; }
        };
        QueryEngine engine = newEngineWithClient(capture, List.of());
        engine.setMemorySection("# memories\n");
        engine.setPlanModeSuffix("# plan\n");

        engine.query("hi").forEach(e -> {});

        String s = seenSystem.get();
        int memoryIdx = s.indexOf("memories");
        int planIdx = s.indexOf("# plan");
        assertTrue(memoryIdx >= 0 && planIdx >= 0, "both sections should be present: " + s);
        assertTrue(memoryIdx < planIdx, "memory should come before plan in system prompt: " + s);
    }

    private QueryEngine newEngineWithClient(ChatClient c, List<Tool> tools) {
        AppState app = new AppState("sid", Path.of("/tmp"));
        app.toolPool().addAll(tools);
        return new QueryEngine(app, c,
                PermissionPolicy.allowAll(), "BASE PROMPT", m -> {});
    }
}
