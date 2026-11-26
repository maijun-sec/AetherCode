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
import org.aethercode.core.permission.PermissionResult;
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
 * R415 (2026-11-26) — the engine's own verdict goes on the wire, and it
 * goes there BEFORE the turn ends.
 *
 * <h2>★ WHY THE ORDER MATTERS ★</h2>
 * <p>The verdict is emitted as a {@code side_note}, and the turn ends
 * with a {@code RunEnd}. A client that stops listening at the RunEnd
 * would never see the verdict — and the verdict is the whole point of
 * the round. So the note must come first.
 *
 * <h2>★ WHY A BEHAVIOURAL TEST AND NOT A SOURCE PIN ★</h2>
 * <p>The engine's other terminal contracts are pinned by source-scanning
 * (R735), because wiring a stub client was fragile. This one can be
 * driven end to end with the same stub the simple-chat test uses — and a
 * source pin could not tell "the probe was asked" from "the probe was
 * asked and its answer was dropped".
 */
class QueryEngineR415Test {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void theVerdictIsEmittedBeforeTheTurnEnds() {
        AtomicInteger probeCalls = new AtomicInteger();
        QueryEngine engine = newEngine();
        engine.setConsistencyProbe(() -> {
            probeCalls.incrementAndGet();
            return "[CONSISTENCY CHECK FAILED] MAX_RETRIES at src/LegacyConfig.java:7 has the value 3";
        });

        List<StreamEvent> events = new ArrayList<>();
        engine.query("change the retry limit").forEach(events::add);

        assertEquals(1, probeCalls.get(), "the probe must be asked exactly once per turn");

        int noteAt = -1;
        int endAt = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof StreamEvent.SideNote sn
                    && "consistency".equals(sn.kind())) {
                noteAt = i;
            }
            if (events.get(i) instanceof StreamEvent.RunEnd && endAt < 0) {
                endAt = i;
            }
        }
        assertTrue(noteAt >= 0, "the verdict must reach the wire: " + events);
        assertTrue(endAt >= 0, "the turn must end: " + events);
        assertTrue(noteAt < endAt,
                "the verdict must arrive BEFORE the run ends, or a client that stops "
                        + "listening at RunEnd never sees it: " + events);
        StreamEvent.SideNote note = (StreamEvent.SideNote) events.get(noteAt);
        assertTrue(note.message().contains("CONSISTENCY CHECK FAILED"),
                "the note must carry the report: " + note.message());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aProbeThatSaysNothingChangesNothing() {
        // ★ SILENCE IS A VALID ANSWER ★ most turns change no
        // value-bearing declaration, and a note on every turn would be
        // exactly the noise the R208 side-note contract forbids.
        QueryEngine engine = newEngine();
        engine.setConsistencyProbe(() -> null);

        List<StreamEvent> events = new ArrayList<>();
        engine.query("hello").forEach(events::add);

        assertTrue(events.stream().noneMatch(e -> e instanceof StreamEvent.SideNote sn
                        && "consistency".equals(sn.kind())),
                "an empty verdict must not produce a note: " + events);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aBrokenProbeDoesNotBreakTheTurn() {
        // ★ A BROKEN PROBE MUST NEVER BREAK A TURN ★ the check is a
        // report, not a gate: the user's work matters more than the
        // verdict.
        QueryEngine engine = newEngine();
        engine.setConsistencyProbe(() -> {
            throw new RuntimeException("boom");
        });

        List<StreamEvent> events = new ArrayList<>();
        engine.query("hello").forEach(events::add);

        assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.RunEnd),
                "the turn must still end: " + events);
    }

    // ----- helpers (the same stub the simple-chat test uses) -----

    private static QueryEngine newEngine() {
        ChatClient oneShot = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                return Stream.of(
                        new StreamEvent.RunStart("r1", "MiniMax-M3"),
                        new StreamEvent.TextDelta("done"),
                        new StreamEvent.RunEnd("stop", List.of(new ContentBlock.TextBlock("done"))));
            }

            @Override
            public String modelId() {
                return "MiniMax-M3";
            }
        };
        AppState state = new AppState("s1", Path.of(""));
        PermissionPolicy allowAll =
                (tool, input, ctx) -> CompletableFuture.completedFuture(new PermissionResult.Allow(Map.of()));
        StreamingToolExecutor noopExec = new StreamingToolExecutor(allowAll, 1) {
            @Override
            public java.util.stream.Stream<Event> run(
                    List<ContentBlock.ToolUseBlock> batch, AppState appState) {
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
        return new QueryEngine(state, oneShot, allowAll, "", null, noopExec, null, null);
    }
}
