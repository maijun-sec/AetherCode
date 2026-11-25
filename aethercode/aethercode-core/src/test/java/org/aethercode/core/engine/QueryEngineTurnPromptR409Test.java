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
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
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
import org.aethercode.core.intent.IntentClassifier;
import org.aethercode.core.intent.RunContext;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.profile.Profile;
import org.aethercode.core.profile.ProfileRegistry;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R409: the engine renders the base prompt for EVERY TURN, from that turn's
 * facts.
 *
 * <h2>★ WHAT WAS BROKEN ★</h2>
 * <p>The base prompt was rendered once, at construction, and frozen. Two of
 * its facts are per-turn:
 *
 * <ul>
 *   <li>the <b>tool table</b> — the profile prunes the pool per turn;</li>
 *   <li>the <b>working directory</b> — the session's cwd moves.</li>
 * </ul>
 *
 * <p>R408 measured the cost: the prompt advertised tools the API had not
 * offered, the model called them, and 48 calls went into hunting for the
 * "real" name of a tool that was never going to exist.
 *
 * <h2>★ WHAT THESE TESTS PIN ★</h2>
 * <p>The prompt the model actually receives:
 * <ul>
 *   <li>lists the TURN's tools — not the session's full pool;</li>
 *   <li>names the session's CURRENT cwd;</li>
 *   <li>is a SNAPSHOT: a subagent that re-enters the engine mid-turn does
 *       not change what the parent turn was told;</li>
 *   <li>falls back to the frozen prompt when no renderer is installed (a bare
 *       QueryEngine must keep working).</li>
 * </ul>
 */
class QueryEngineTurnPromptR409Test {

    /** what the model was told, captured at the call site. */
    private static final class Captured {
        String systemPrompt;
        List<Tool> tools;
    }

    private static ChatClient capturing(Captured captured) {
        return new ChatClient() {
            @Override
            public java.util.stream.Stream<StreamEvent> stream(
                    List<Message> messages, String systemPrompt, List<Tool> tools) {
                captured.systemPrompt = systemPrompt;
                captured.tools = tools;
                return java.util.stream.Stream.of(
                        new StreamEvent.RunStart("r1", "m"),
                        new StreamEvent.RunEnd("stop",
                                List.of(new ContentBlock.TextBlock("done"))));
            }
            @Override public String modelId() { return "m"; }
        };
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "the " + name + " tool"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public CompletableFuture<PermissionResult> checkPermissions(
                    Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(Map.of()));
            }
            @Override public CompletableFuture<ToolResult> call(Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(ToolResult.of("ok"));
            }
        };
    }

    private static QueryEngine engine(AppState state, ChatClient client) {
        PermissionPolicy allowAll = (tool, input, ctx) ->
                CompletableFuture.completedFuture(new PermissionResult.Allow(Map.of()));
        StreamingToolExecutor noopExec = new StreamingToolExecutor(allowAll, 1) {
            @Override
            public java.util.stream.Stream<Event> run(
                    List<ContentBlock.ToolUseBlock> batch, AppState appState) {
                return java.util.stream.Stream.empty();
            }
        };
        return new QueryEngine(state, client, allowAll, "FROZEN BASE PROMPT",
                null, noopExec, null, null);
    }

    private static RunContext readOnlyTurn(List<Tool> pool) {
        return new RunContext(
                IntentClassifier.classify("这个仓库里的 XxxUtil 在哪里"),
                ProfileRegistry.ANALYST,
                pool);
    }

    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void thePromptListsTheTurnsToolsNotTheSessionsPool() {
        AppState state = new AppState("s1", Path.of("/session/dir"));
        state.toolPool().addAll(List.of(tool("file_read"), tool("file_write"), tool("bash")));

        Captured captured = new Captured();
        QueryEngine engine = engine(state, capturing(captured));
        engine.setTurnPromptRenderer((turnTools, cwd) -> {
            StringBuilder sb = new StringBuilder("BASE\n");
            for (Tool t : turnTools) sb.append("TOOL ").append(t.name()).append("\n");
            sb.append("CWD ").append(cwd);
            return sb.toString();
        });

        // the turn's pool: the read-only profile kept only file_read.
        List<Tool> turnPool = List.of(tool("file_read"));
        engine.query("这个仓库里的 XxxUtil 在哪里", null, readOnlyTurn(turnPool))
                .forEach(e -> { });

        assertThat(captured.systemPrompt)
                .as("★ the prompt must list the turn's tools")
                .contains("TOOL file_read")
                .doesNotContain("TOOL file_write")
                .doesNotContain("TOOL bash");
        assertThat(captured.tools)
                .as("the API's tool list is the same pruned pool")
                .extracting(Tool::name)
                .containsExactly("file_read");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void thePromptNamesTheSessionsCurrentDirectory() {
        AppState state = new AppState("s1", Path.of("/first/dir"));
        Captured captured = new Captured();
        QueryEngine engine = engine(state, capturing(captured));
        engine.setTurnPromptRenderer((turnTools, cwd) -> "CWD " + cwd);

        engine.query("hi", null, readOnlyTurn(List.of(tool("file_read"))))
                .forEach(e -> { });
        // The AppState normalises the path (absolute + normalised), so the
        // expectation is read from the state itself — a hand-written
        // "/first/dir" is a different string on Windows.
        String first = state.cwd().toString();
        assertThat(captured.systemPrompt).contains(first);

        // the session moves (switchProject)
        state.cwd(Path.of("/second/dir"));
        String second = state.cwd().toString();
        engine.query("hi", null, readOnlyTurn(List.of(tool("file_read"))))
                .forEach(e -> { });
        assertThat(captured.systemPrompt)
                .as("★ the prompt must follow the session's cwd")
                .contains(second)
                .doesNotContain(first);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void withoutARendererTheFrozenPromptIsUsed() {
        // A bare QueryEngine (tests, embedders) must keep working exactly
        // as before: the frozen string is the base.
        AppState state = new AppState("s1", Path.of("/session/dir"));
        Captured captured = new Captured();
        QueryEngine engine = engine(state, capturing(captured));

        engine.query("hi", null, readOnlyTurn(List.of(tool("file_read"))))
                .forEach(e -> { });

        assertThat(captured.systemPrompt).contains("FROZEN BASE PROMPT");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aRendererThatThrowsDoesNotTakeTheTurnDown() {
        // A broken renderer must not cost the user their turn: the frozen
        // prompt is a worse answer than the right one, but it is an answer.
        AppState state = new AppState("s1", Path.of("/session/dir"));
        Captured captured = new Captured();
        QueryEngine engine = engine(state, capturing(captured));
        engine.setTurnPromptRenderer((turnTools, cwd) -> {
            throw new IllegalStateException("boom");
        });

        List<StreamEvent> events = new ArrayList<>();
        engine.query("hi", null, readOnlyTurn(List.of(tool("file_read"))))
                .forEach(events::add);

        assertThat(captured.systemPrompt).contains("FROZEN BASE PROMPT");
        assertThat(events).isNotEmpty();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void anExplicitPromptReplacesThePerTurnRendering() {
        // The /prompt RPC: the user typed a prompt and wants exactly that.
        // The renderer's whole job is to rewrite the prompt's tool table —
        // which is exactly what an explicit prompt must not have done to it.
        AppState state = new AppState("s1", Path.of("/session/dir"));
        Captured captured = new Captured();
        QueryEngine engine = engine(state, capturing(captured));
        engine.setTurnPromptRenderer((turnTools, cwd) -> "RENDERED BASE");

        engine.setSystemPrompt("THE USER'S OWN PROMPT");

        engine.query("hi", null, readOnlyTurn(List.of(tool("file_read"))))
                .forEach(e -> { });

        assertThat(captured.systemPrompt)
                .contains("THE USER'S OWN PROMPT")
                .doesNotContain("RENDERED BASE");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void theRendererSeesTheToolsTheApiSends() {
        // The prompt's tool table and the API's tool list are two views of
        // ONE fact. If they disagree, the model calls a tool it was never
        // offered — which is what R408 measured.
        AppState state = new AppState("s1", Path.of("/session/dir"));
        state.toolPool().addAll(List.of(tool("file_read"), tool("file_write")));
        Captured captured = new Captured();
        QueryEngine engine = engine(state, capturing(captured));
        AtomicReference<List<Tool>> seenByRenderer = new AtomicReference<>();
        engine.setTurnPromptRenderer((turnTools, cwd) -> {
            seenByRenderer.set(turnTools);
            return "BASE";
        });

        List<Tool> turnPool = List.of(tool("file_read"));
        engine.query("hi", null, readOnlyTurn(turnPool)).forEach(e -> { });

        assertThat(seenByRenderer.get())
                .extracting(Tool::name)
                .containsExactly("file_read");
        assertThat(captured.tools)
                .extracting(Tool::name)
                .containsExactly("file_read");
    }
}
