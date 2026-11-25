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

package org.aethercode.sdk;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R411: the CAPABILITY axis at the SDK level — the layer where the
 * profile is actually resolved and the pool is actually pruned.
 *
 * <h2>★ WHY THIS TEST IS AT THE SDK LEVEL ★</h2>
 * <p>The core tests pin the classifier and the registry. Neither of them
 * can see the line that actually matters: the SDK resolves the posture
 * from the WHOLE verdict, and that resolution is a one-line call — a
 * line that can be quietly changed to pass only the DOMAIN half, and
 * then every core test stays green while the product goes back to
 * pruning the write channel off a turn that asked for a file.
 *
 * <p>★ THAT IS EXACTLY WHAT THIS TEST IS FOR ★ it runs the real
 * {@code AetherCodeEngine#query} with no profile override, and asks what
 * the MODEL is shown.
 */
class AetherCodeEngineCapabilityR411Test {

    @TempDir Path root;

    private static final class Captured {
        final AtomicReference<String> systemPrompt = new AtomicReference<>();
        final AtomicReference<List<Tool>> tools = new AtomicReference<>();
    }

    private static ChatClient capturing(Captured captured) {
        return new ChatClient() {
            @Override
            public java.util.stream.Stream<StreamEvent> stream(
                    List<Message> messages, String systemPrompt, List<Tool> tools) {
                captured.systemPrompt.set(systemPrompt);
                captured.tools.set(tools);
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

    private AetherCodeEngine engine(List<Tool> pool) {
        AetherCodeEngine e = new AetherCodeEngine.Builder()
                .cwd(root.resolve("work"))
                .tools(pool)
                .sessionId("s1")
                .build();
        // ★ NO profile override ★ the classifier decides — that is the
        // path under test.
        return e;
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theWriteToolReachesTheModelWhenTheTurnAsksForAFile() {
        // ★ THE USER-VISIBLE OUTCOME ★ R408's S4, through the real
        // engine: the model must be SHOWN the write tool, or the file
        // will never land.
        Captured captured = new Captured();
        AetherCodeEngine e = engine(List.of(tool("file_read"), tool("file_write")));
        e.setChatClient(capturing(captured));

        e.query("写一首关于秋天的打油诗，保存到 poem.txt").forEach(ev -> { });

        assertThat(captured.tools.get())
                .as("★ a turn that asks for a file must be able to write one")
                .extracting(Tool::name)
                .contains("file_write");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theWriteToolDoesNotReachTheModelForAPoemWithoutADestination() {
        // ★ THE MIRROR ★ R408 measured this one as CORRECT: a poem in
        // the chat needs no file, so the read-only posture must hold.
        Captured captured = new Captured();
        AetherCodeEngine e = engine(List.of(tool("file_read"), tool("file_write")));
        e.setChatClient(capturing(captured));

        e.query("写一首关于秋天的打油诗").forEach(ev -> { });

        assertThat(captured.tools.get())
                .as("a poem in the chat must not be given the write channel")
                .extracting(Tool::name)
                .doesNotContain("file_write");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theUsersProfileOverrideStillWins() {
        // ★ R401's contract, unchanged ★ the user's override beats the
        // classifier. The capability axis does NOT override the user: an
        // explicit "read-only" is a decision, and the axis is a guess.
        Captured captured = new Captured();
        AetherCodeEngine e = engine(List.of(tool("file_read"), tool("file_write")));
        e.appState().profileOverride("analyst");
        e.setChatClient(capturing(captured));

        e.query("写一首关于秋天的打油诗，保存到 poem.txt").forEach(ev -> { });

        assertThat(captured.tools.get())
                .as("the user's own posture override must win")
                .extracting(Tool::name)
                .doesNotContain("file_write");
    }
}
