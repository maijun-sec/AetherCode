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
import org.aethercode.core.scope.SessionPaths;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R417 — the turn must be told where the SCRATCH AREA is.
 *
 * <h2>★ WHY A PROMPT LINE ★</h2>
 * <p>R416's live matrix measured a turn that left nine files in the user's
 * tree — a {@code .verify/} scaffolding directory, a verification script,
 * a transcript of the run. All of them were LEGAL writes (they were
 * inside the tree), so a tighter boundary could not have helped: the
 * model chose the wrong place.
 *
 * <p>⇒ the engine now owns a per-session scratch area and ALLOWS writes
 * into it. But a permission is not an instruction: the model has to be
 * TOLD where the scratch area is, or it will never choose it.
 *
 * <h2>★ WHY THE DIRECTORY IS CREATED ★</h2>
 * <p>The file tools create the parent directories they need; the shell
 * does not. A model told about a directory that does not exist will make
 * one in the user's tree instead — which is the exact failure this whole
 * mechanism exists to prevent.
 */
class QueryEngineScratchR417Test {

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R417: the turn's prompt names the session's scratch area")
    void theTurnsPromptNamesTheScratchArea() {
        List<String> systemPrompts = new ArrayList<>();

        ChatClient oneShot = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                systemPrompts.add(systemPrompt);
                return Stream.of(new StreamEvent.RunEnd("end_turn", List.of()));
            }
            @Override public String modelId() { return "test-model"; }
        };

        QueryEngine engine = newEngineWithClient(oneShot);
        engine.query("do the thing").forEach(ev -> { });

        assertTrue(systemPrompts.size() >= 1, "the engine must have issued a turn");
        String prompt = systemPrompts.get(0);
        Path scratch = SessionPaths.scratchRoot("s1");

        assertTrue(prompt.contains(scratch.toString()),
                "the prompt must name the scratch area, got: " + prompt);
        assertTrue(prompt.contains("AETHERCODE_SCRATCH"),
                "the prompt must name the environment variable the shell sees, got: " + prompt);
        assertTrue(prompt.contains("not in the user's project tree"),
                "the prompt must say WHERE the scratch area is relative to the tree, got: " + prompt);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("R417: the scratch area exists by the time the turn runs")
    void theScratchAreaExistsByTheTimeTheTurnRuns() {
        Path scratch = SessionPaths.scratchRoot("s1");

        ChatClient oneShot = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                return Stream.of(new StreamEvent.RunEnd("end_turn", List.of()));
            }
            @Override public String modelId() { return "test-model"; }
        };

        QueryEngine engine = newEngineWithClient(oneShot);
        engine.query("do the thing").forEach(ev -> { });

        assertTrue(Files.isDirectory(scratch),
                "the shell cannot write into a directory that does not exist — and a model told "
                        + "about a missing directory will make one in the user's tree instead. "
                        + "Expected: " + scratch);
    }

    // ----- helpers -----

    private static QueryEngine newEngineWithClient(ChatClient client) {
        AppState state = new AppState("s1", Path.of(""));
        org.aethercode.core.engine.PermissionPolicy allowAll =
                (tool, input, ctx) -> CompletableFuture.completedFuture(
                        new org.aethercode.core.permission.PermissionResult.Allow(Map.of()));
        org.aethercode.core.engine.StreamingToolExecutor exec =
                new org.aethercode.core.engine.StreamingToolExecutor(allowAll, 1) {
                    @Override
                    public java.util.stream.Stream<Event> run(
                            List<ContentBlock.ToolUseBlock> batch, AppState appState) {
                        return java.util.stream.Stream.of(new Event.BatchEnd());
                    }
                };
        return new QueryEngine(state, client, allowAll, "", null, exec, null, null);
    }
}
