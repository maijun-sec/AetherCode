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
 * R409: the engine's prompt and the engine's API call must agree.
 *
 * <h2>★ THE DEFECT THIS PINS ★</h2>
 * <p>The API call carried the turn's PRUNED tool pool (R401), while the
 * prompt carried the FULL pool — rendered once, at construction. The model
 * therefore read a tool table that advertised tools the API had not
 * offered, called them, and got "unknown tool" back. R408 measured it on
 * the live daemon: 48 calls to a shell that was not in the turn's pool,
 * each one answered "unknown tool", the model then hunting the tree for
 * the "real" name of a tool that was never going to exist.
 *
 * <h2>★ WHY THIS TEST IS AT THE SDK LEVEL ★</h2>
 * <p>The two halves live in two modules: the PRUNE happens in the SDK
 * ({@code AetherCodeEngine#query}), the PROMPT is rendered by the SDK too
 * (it owns the {@code SystemPrompt}). A core-level test could only see one
 * half at a time — and a defect where the halves disagree is exactly what
 * a single-half test cannot see.
 *
 * <p>So this test asserts the AGREEMENT, which is the thing that was
 * broken:
 * <ul>
 *   <li>the prompt's tool table == the API's tool list;</li>
 *   <li>the prompt names the session's CURRENT cwd;</li>
 *   <li>the {@code /prompt} preview shows the same tools the model gets.</li>
 * </ul>
 */
class AetherCodeEngineTurnPromptR409Test {

    @TempDir Path root;

    /** the prompt + tool list the model actually received. */
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

    private AetherCodeEngine engine(Path cwd, List<Tool> pool) {
        AetherCodeEngine e = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(pool)
                .sessionId("s1")
                .build();
        // the read-only posture, stated explicitly — the test is about the
        // prompt, not about the classifier.
        e.appState().profileOverride("analyst");
        return e;
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void thePromptAndTheApiCarryTheSameTools() {
        Path cwd = root.resolve("work");
        List<Tool> pool = List.of(tool("file_read"), tool("file_write"), tool("bash"));
        AetherCodeEngine e = engine(cwd, pool);
        Captured captured = new Captured();
        e.setChatClient(capturing(captured));

        e.query("这个仓库里的 XxxUtil 在哪里").forEach(ev -> { });

        String prompt = captured.systemPrompt.get();
        // ★ The tool table renders each tool as "## <name>". The default
        // identity text MENTIONS `file_write` ("you MUST call the actual
        // `file_write` tool"), so an assertion over the whole prompt
        // ("the prompt does not contain file_write") would be a test of the
        // identity text, not of the table. The table's own shape is the
        // thing under test.
        assertThat(prompt)
                .as("★ the prompt's TOOL TABLE must not advertise a tool the turn cannot call")
                .contains("## file_read")
                .doesNotContain("## file_write")
                .doesNotContain("## bash");
        assertThat(captured.tools.get())
                .as("the API's list is the pruned pool")
                .extracting(Tool::name)
                .containsExactly("file_read");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void thePromptNamesTheSessionsCurrentDirectory() {
        Path cwd = root.resolve("work");
        AetherCodeEngine e = engine(cwd, List.of(tool("file_read")));
        Captured captured = new Captured();
        e.setChatClient(capturing(captured));

        e.query("读一下").forEach(ev -> { });
        assertThat(captured.systemPrompt.get())
                .contains(cwd.toString());

        // the session moves (switchProject)
        Path moved = root.resolve("moved");
        e.setCwd(moved);
        e.query("读一下").forEach(ev -> { });
        assertThat(captured.systemPrompt.get())
                .as("★ the prompt must follow the session's cwd")
                .contains(moved.toString());
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void thePromptPreviewShowsTheToolsTheModelGets() {
        // The /prompt preview is the user's window into the prompt. If it
        // shows a different tool set than the model gets, the window lies
        // — which is the R383 failure mode, one layer down.
        Path cwd = root.resolve("work");
        AetherCodeEngine e = engine(cwd, List.of(tool("file_read"), tool("file_write")));
        Captured captured = new Captured();
        e.setChatClient(capturing(captured));

        e.query("读一下").forEach(ev -> { });

        String preview = e.currentRenderedPrompt().text();
        assertThat(preview)
                .contains("## file_read")
                .doesNotContain("## file_write");
        assertThat(captured.systemPrompt.get())
                .contains("## file_read")
                .doesNotContain("## file_write");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void thePromptCarriesNoToolSchema() {
        // The schema is the API's business. A second copy in the prompt is
        // a second chance to be wrong (R408 P0-5).
        Path cwd = root.resolve("work");
        AetherCodeEngine e = engine(cwd, List.of(tool("file_read"), tool("file_write")));
        Captured captured = new Captured();
        e.setChatClient(capturing(captured));

        e.query("读一下").forEach(ev -> { });

        assertThat(captured.systemPrompt.get())
                .doesNotContain("Input schema");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void thePreviewFollowsAVerbatimPromptReplacement() {
        // The /prompt RPC replaces the prompt outright. The preview must
        // show THAT -- not the tool table of the turn that ran under the
        // previous prompt, and not a table the user never asked for.
        Path cwd = root.resolve("work");
        AetherCodeEngine e = engine(cwd, List.of(tool("file_read"), tool("file_write")));
        Captured captured = new Captured();
        e.setChatClient(capturing(captured));

        e.query("读一下").forEach(ev -> { });

        e.setSystemPromptText("THE USER'S OWN PROMPT");

        assertThat(e.currentRenderedPrompt().text())
                .as("★ the preview must show the prompt the model will actually get")
                .contains("THE USER'S OWN PROMPT")
                .doesNotContain("## file_read");
    }
}
