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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.aethercode.core.app.AppState;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.aethercode.core.transcript.FileTouchRecorder;
import org.junit.jupiter.api.Test;

/**
 * R407: the executor carries the recorder into the tool's context.
 *
 * <p>★ WHY THIS LAYER MATTERS ★ the write tool reports the path it is
 * about to write, and it finds the recorder in the
 * {@link Tool.CallContext}. The executor is what builds that context — so
 * if it stops carrying the recorder, the whole R407 chain goes quiet and
 * every rewind silently undoes nothing again. That is exactly the shape
 * of failure this layer has to be pinned against.
 */
class StreamingToolExecutorR407Test {

    /** A recorder that just remembers what it was told. */
    private static final class Recorder implements FileTouchRecorder {
        final List<Path> paths = new ArrayList<>();

        @Override
        public void beforeWrite(Path path) {
            paths.add(path);
        }
    }

    /** A tool that behaves like the write tools: it reports before it writes. */
    private static Tool writingTool(String name) {
        return Tools.build(new ToolDef(name, "a tool that writes", Map.of(),
                (in, ctx) -> {
                    FileTouchRecorder.noteBeforeWrite(ctx, Path.of(String.valueOf(in.get("file_path"))));
                    return CompletableFuture.completedFuture(Tool.ToolResult.of("ok"));
                }));
    }

    @Test
    void theRecorderReachesTheTool() {
        Recorder recorder = new Recorder();
        AppState st = new AppState("s1", Path.of(""));
        st.toolPool().add(writingTool("file_write"));
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 4);
        exec.withFileTouchRecorder(recorder);

        exec.run(List.of(new ContentBlock.ToolUseBlock(
                        "1", "file_write", Map.of("file_path", "C:\\work\\a.txt"))),
                st).collect(Collectors.toList());

        assertEquals(List.of(Path.of("C:\\work\\a.txt").toAbsolutePath().normalize()),
                recorder.paths,
                "the executor must carry the recorder into the tool's context");
    }

    @Test
    void withoutARecorderTheToolStillRuns() {
        AppState st = new AppState("s1", Path.of(""));
        st.toolPool().add(writingTool("file_write"));
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 4);
        // no recorder installed at all
        var events = exec.run(List.of(new ContentBlock.ToolUseBlock(
                        "1", "file_write", Map.of("file_path", "C:\\work\\a.txt"))),
                st).collect(Collectors.toList());
        assertTrue(events.stream().anyMatch(e -> e instanceof StreamingToolExecutor.Event.Completed
                        && !((StreamingToolExecutor.Event.Completed) e).isError()),
                "the tool must still run when nobody is listening");
    }
}
