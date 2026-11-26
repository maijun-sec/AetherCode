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

package org.aethercode.hooks;

import org.aethercode.core.app.AppState;
import org.aethercode.core.engine.StreamingToolExecutor;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R415: the pre-hook bridge carries the refusal's REASON.
 *
 * <p>★ WHAT WAS WRONG ★ {@code asPreBridge} used to answer a boolean:
 * {@code return o instanceof Hook.Outcome.Continue;}. A {@code Block}
 * became {@code false}, and the executor emitted the literal
 * "blocked by pre-hook". The hook's explanation — the part that tells the
 * model what to do instead — was discarded on the way.
 */
class HooksAsPreBridgeR415Test {

    @Test
    void aBlockCarriesItsReason(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        HookRegistry reg = new HookRegistry();
        reg.register(new Hook() {
            @Override public Kind kind() { return Kind.PRE_TOOL_USE; }
            @Override public CompletableFuture<Outcome> run(HookContext ctx) {
                return CompletableFuture.completedFuture(
                        new Outcome.Block("read the file first, or pass overwrite:true"));
            }
        });
        StreamingToolExecutor.HookBridge bridge = Hooks.asPreBridge(reg);
        StreamingToolExecutor.HookBridge.Outcome out = bridge.runWithOutcome(
                StreamingToolExecutor.HookBridge.Phase.PRE,
                dummyTool("file_write"),
                "call-1",
                Map.of("file_path", tmp.resolve("x.txt").toString()),
                null,
                appState);
        assertThat(out).isNotNull();
        assertThat(out.verdict()).isEqualTo(StreamingToolExecutor.HookBridge.Verdict.BLOCK);
        assertThat(out.reason()).contains("overwrite:true");
    }

    @Test
    void theLegacyBooleanEntryPointStillWorks(@TempDir Path tmp) {
        // ★ THE LEGACY CALLERS KEEP WORKING ★ a bridge that only
        // implements run(boolean) is translated, not broken.
        AppState appState = new AppState("s1", tmp);
        HookRegistry reg = new HookRegistry();
        reg.register(new Hook() {
            @Override public Kind kind() { return Kind.PRE_TOOL_USE; }
            @Override public CompletableFuture<Outcome> run(HookContext ctx) {
                return CompletableFuture.completedFuture(new Outcome.Continue());
            }
        });
        StreamingToolExecutor.HookBridge bridge = Hooks.asPreBridge(reg);
        assertThat(bridge.run(StreamingToolExecutor.HookBridge.Phase.PRE,
                dummyTool("file_write"), "call-2", Map.of(), null, appState)).isTrue();
    }

    @Test
    void aContinueMeansContinue(@TempDir Path tmp) {
        AppState appState = new AppState("s1", tmp);
        HookRegistry reg = new HookRegistry();
        reg.register(new Hook() {
            @Override public Kind kind() { return Kind.PRE_TOOL_USE; }
            @Override public CompletableFuture<Outcome> run(HookContext ctx) {
                return CompletableFuture.completedFuture(new Outcome.Continue());
            }
        });
        StreamingToolExecutor.HookBridge bridge = Hooks.asPreBridge(reg);
        StreamingToolExecutor.HookBridge.Outcome out = bridge.runWithOutcome(
                StreamingToolExecutor.HookBridge.Phase.PRE,
                dummyTool("file_write"), "call-3", Map.of(), null, appState);
        assertThat(out.verdict()).isEqualTo(StreamingToolExecutor.HookBridge.Verdict.CONTINUE);
    }

    private static Tool dummyTool(String name) {
        return Tools.build(new ToolDef(name, "test", Map.of(),
                (in, ctx) -> CompletableFuture.completedFuture(Tool.ToolResult.of("ok"))));
    }
}
