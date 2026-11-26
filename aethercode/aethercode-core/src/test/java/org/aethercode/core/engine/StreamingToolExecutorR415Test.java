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
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R415: a refusal must reach the model WITH its reason.
 *
 * <h2>★ WHAT WAS WRONG ★</h2>
 * <p>The pre-hook path collapsed a {@code Block} into a boolean, and the
 * executor emitted the literal {@code "blocked by pre-hook"}. The hook's
 * own explanation — the part that says what to do next — was thrown away.
 * A model told "no" with no reason cannot comply; it can only guess.
 *
 * <p>★ THE WRITE GUARD WAS THE VICTIM ★ its {@code BLOCK_MESSAGE} tells
 * the model to read the file first, or pass {@code overwrite: true}, or
 * use {@code file_edit}. That sentence had never reached a model.
 */
class StreamingToolExecutorR415Test {

    @Test
    void theBlockReasonReachesTheToolResult() {
        AtomicInteger ran = new AtomicInteger();
        Tool t = Tools.build(new ToolDef("t", "t", Map.of(),
                (in, ctx) -> {
                    ran.incrementAndGet();
                    return CompletableFuture.completedFuture(Tool.ToolResult.of("ok"));
                }));
        AppState st = new AppState("s1", Path.of(""));
        st.toolPool().add(t);
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 4);
        exec.withPreHook(new StreamingToolExecutor.HookBridge() {
            @Override
            public boolean run(StreamingToolExecutor.HookBridge.Phase phase, Tool tool, String id,
                               Map<String, Object> input, Tool.ToolResult result, AppState appState) {
                return false;
            }

            @Override
            public StreamingToolExecutor.HookBridge.Outcome runWithOutcome(
                    StreamingToolExecutor.HookBridge.Phase phase, Tool tool, String id,
                    Map<String, Object> input, Tool.ToolResult result, AppState appState) {
                return StreamingToolExecutor.HookBridge.Outcome.block(
                        "Refusing to overwrite existing file. Read it first.");
            }
        });
        var events = exec.run(
                List.of(new ContentBlock.ToolUseBlock("1", "t", Map.of())),
                st
        ).collect(Collectors.toList());

        assertThat(ran.get()).isZero();
        assertThat(events).anyMatch(e -> e instanceof StreamingToolExecutor.Event.Completed
                && ((StreamingToolExecutor.Event.Completed) e).output().toString()
                        .contains("Refusing to overwrite existing file"));
    }

    @Test
    void aBlockWithoutAReasonStillSaysSomething() {
        // ★ NO EMPTY REFUSAL ★ a bridge that blocks without a reason must
        // not produce an empty tool result — "blocked with no explanation"
        // is exactly what this change exists to stop.
        AtomicInteger ran = new AtomicInteger();
        Tool t = Tools.build(new ToolDef("t", "t", Map.of(),
                (in, ctx) -> {
                    ran.incrementAndGet();
                    return CompletableFuture.completedFuture(Tool.ToolResult.of("ok"));
                }));
        AppState st = new AppState("s1", Path.of(""));
        st.toolPool().add(t);
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 4);
        exec.withPreHook((phase, tool, id, input, result, appState) -> false);
        var events = exec.run(
                List.of(new ContentBlock.ToolUseBlock("1", "t", Map.of())),
                st
        ).collect(Collectors.toList());

        assertThat(events).anyMatch(e -> e instanceof StreamingToolExecutor.Event.Completed
                && ((StreamingToolExecutor.Event.Completed) e).output().toString().contains("blocked"));
    }
}
