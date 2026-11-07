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
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R388l — the approval comment reaches the tool_result the MODEL reads.
 *
 * <p>The prompter test proves the comment survives the wire. This test
 * proves the LAST step: the executor puts it on the tool_result.
 *
 * <p>Why it needs its own test: the comment can die at three places, and
 * each one has a different owner.
 *
 * <ol>
 *   <li>the wire — the client must SEND it ({@code permissionResponse});</li>
 *   <li>the prompter — the daemon must CARRY it into the
 *       {@code PermissionResult};</li>
 *   <li>the executor — the note must be APPENDED to the tool_result,
 *       because that string is what the model reads on the next turn.</li>
 * </ol>
 *
 * <p>A test of the first two says nothing about the third. This is the
 * same shape as Lesson 1329: the client and the daemon are two systems,
 * and so are the daemon and the model.
 */
class StreamingToolExecutorR388lApprovalNoteTest {

    /** A tool that returns a fixed string. */
    private static Tool okTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public boolean isConcurrencySafe(Map<String, Object> input) { return true; }
            @Override public CompletableFuture<PermissionResult> checkPermissions(
                    Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            @Override public CompletableFuture<Tool.ToolResult> call(
                    Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(Tool.ToolResult.of("wrote 47 bytes"));
            }
        };
    }

    /** A policy that approves with a NOTE. */
    private static PermissionPolicy allowWithNote(String note) {
        return (tool, input, ctx) -> CompletableFuture.completedFuture(
                new PermissionResult.Allow(input, note));
    }

    @Test
    void theApprovalNoteIsOnTheToolResultTheModelReads() {
        Tool t = okTool("file_write");
        AppState st = new AppState("s1", Path.of(""));
        st.toolPool().add(t);
        StreamingToolExecutor exec = new StreamingToolExecutor(
                allowWithNote("yes, but keep the original header"), 4);

        var events = exec.run(
                List.of(new ContentBlock.ToolUseBlock("1", "file_write", Map.of())),
                st).collect(Collectors.toList());

        var completed = events.stream()
                .filter(e -> e instanceof StreamingToolExecutor.Event.Completed)
                .map(e -> (StreamingToolExecutor.Event.Completed) e)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Completed event"));

        assertThat(completed.isError()).isFalse();
        // ★ This string IS the tool_result the model reads.
        assertThat(String.valueOf(completed.output()))
                .contains("wrote 47 bytes")
                .contains("[user's note] yes, but keep the original header");
    }

    @Test
    void anApprovalWithoutANoteLeavesTheToolResultAlone() {
        Tool t = okTool("file_write");
        AppState st = new AppState("s1", Path.of(""));
        st.toolPool().add(t);
        StreamingToolExecutor exec = new StreamingToolExecutor(
                PermissionPolicy.allowAll(), 4);

        var events = exec.run(
                List.of(new ContentBlock.ToolUseBlock("1", "file_write", Map.of())),
                st).collect(Collectors.toList());

        var completed = events.stream()
                .filter(e -> e instanceof StreamingToolExecutor.Event.Completed)
                .map(e -> (StreamingToolExecutor.Event.Completed) e)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Completed event"));

        // ★ Backward compatibility: an approval with no comment produces
        // the PRE-R388l tool_result, byte for byte.
        assertThat(String.valueOf(completed.output())).isEqualTo("wrote 47 bytes");
    }
}
