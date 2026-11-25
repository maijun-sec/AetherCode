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

package org.aethercode.tools.file;

import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.aethercode.core.app.AppState;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.transcript.FileTouchRecorder;
import org.aethercode.tools.edit.NotebookEditTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R407: the WRITE TOOLS report what they are about to touch.
 *
 * <p>★ WHY THE TOOL, NOT THE EXECUTOR ★ the tool already resolved the
 * path against the session's working directory and already refused the
 * paths it may not touch. An executor that re-derived the path would be a
 * SECOND copy of that resolution (R380: a second list drifts), so the
 * tool reports the path it is about to write.
 *
 * <p>★ AND WHY IT MATTERS ★ the checkpoint point BEFORE a turn can only
 * undo that turn if it knows what the file looked like before it — and
 * the only moment that knows is the moment before the write.
 *
 * <p>★ THE CWD TRAVELS PER CALL ★ in the {@code app_state} extra, not in
 * the {@code aethercode.cwd} system property: a system property is global
 * state, and a test that sets it races every other test that reads it.
 */
class FileWriteToolR407Test {

    /** A recorder that just remembers what it was told. */
    private static final class Recorder implements FileTouchRecorder {
        final List<Path> paths = new ArrayList<>();

        @Override
        public void beforeWrite(Path path) {
            paths.add(path);
        }
    }

    private static Tool.CallContext ctxWith(Recorder recorder, Path cwd) {
        Map<String, Object> extras = new HashMap<>();
        extras.put(FileTouchRecorder.EXTRAS_KEY, recorder);
        extras.put("app_state", new AppState("s1", cwd));
        return new Tool.CallContext("s1", null, extras);
    }

    /** A context that carries NO recorder at all. */
    private static Tool.CallContext ctxWithoutRecorder(Path cwd) {
        Map<String, Object> extras = new HashMap<>();
        extras.put("app_state", new AppState("s1", cwd));
        return new Tool.CallContext("s1", null, extras);
    }

    @Test
    void fileWriteReportsThePathItIsAboutToWrite(@TempDir Path tmp) throws Exception {
        Recorder recorder = new Recorder();
        Path f = tmp.resolve("a.txt");
        var res = FileWriteTool.call(
                Map.of("file_path", f.toString(), "content", "hello"),
                ctxWith(recorder, tmp));
        assertFalse(res.isError(), "the write must succeed: " + res.output());
        assertEquals(List.of(f.toAbsolutePath().normalize()), recorder.paths,
                "the tool must report the path it writes");
        assertEquals("hello", Files.readString(f));
    }

    @Test
    void fileEditReportsThePathItIsAboutToWrite(@TempDir Path tmp) throws Exception {
        Recorder recorder = new Recorder();
        Path f = tmp.resolve("b.txt");
        Files.writeString(f, "OLD", StandardCharsets.UTF_8);
        var res = FileEditTool.call(
                Map.of("file_path", f.toString(), "old_string", "OLD", "new_string", "NEW"),
                ctxWith(recorder, tmp));
        assertFalse(res.isError(), "the edit must succeed: " + res.output());
        assertEquals(List.of(f.toAbsolutePath().normalize()), recorder.paths,
                "the tool must report the path it writes");
        assertEquals("NEW", Files.readString(f));
    }

    @Test
    void notebookEditReportsThePathItIsAboutToWrite(@TempDir Path tmp) throws Exception {
        Recorder recorder = new Recorder();
        Path nb = tmp.resolve("nb.ipynb");
        Files.writeString(nb, """
                {"cells": [], "metadata": {}, "nbformat": 4, "nbformat_minor": 5}
                """, StandardCharsets.UTF_8);
        var res = NotebookEditTool.call(
                Map.of("notebook_path", nb.toString(), "operation", "insert",
                        "cell_index", 0, "new_source", "print(1)"),
                ctxWith(recorder, tmp));
        assertFalse(res.isError(), "the notebook edit must succeed: " + res.output());
        assertEquals(List.of(nb.toAbsolutePath().normalize()), recorder.paths,
                "the tool must report the path it writes");
        assertTrue(Files.readString(nb).contains("print(1)"));
    }

    @Test
    void aRefusedWriteReportsNothing(@TempDir Path tmp) throws Exception {
        // the path is OUTSIDE the working directory ⇒ the tool refuses
        // before it writes, so it must not report a touch either: a
        // refused write did not change anything.
        //
        // ★ THE SANDBOX MUST BE ON FOR THIS TO MEAN ANYTHING ★ with
        // AETHERCODE_ALLOW_ANY_PATH=1 the check is bypassed and the test
        // would assert the wrong direction. Same convention as
        // FileWriteToolTest: skip with a clear message.
        assumeThat(System.getenv("AETHERCODE_ALLOW_ANY_PATH"))
                .as("AETHERCODE_ALLOW_ANY_PATH must not be set for sandbox tests to be meaningful")
                .isNull();
        Recorder recorder = new Recorder();
        Path outside = tmp.resolveSibling("r407-outside-" + System.nanoTime() + ".txt");
        var res = FileWriteTool.call(
                Map.of("file_path", outside.toString(), "content", "hello"),
                ctxWith(recorder, tmp));
        assertTrue(res.isError(), "the write must be refused: " + res.output());
        assertTrue(recorder.paths.isEmpty(),
                "a refused write must not report a touch: " + recorder.paths);
    }

    @Test
    void aContextWithoutARecorderIsFine(@TempDir Path tmp) throws Exception {
        // nobody is listening (the CLI path, the unit tests) ⇒ the tool
        // writes as before and does not fail.
        Path f = tmp.resolve("c.txt");
        var res = FileWriteTool.call(
                Map.of("file_path", f.toString(), "content", "hello"),
                ctxWithoutRecorder(tmp));
        assertFalse(res.isError(), "the write must succeed: " + res.output());
        assertEquals("hello", Files.readString(f));
    }
}
