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

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileEditToolTest {

    @Test
    void uniqueReplacementSucceeds(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "hello world\n");
        Tool t = FileEditTool.build();
        Tool.ToolResult res = t.call(Map.of(
                "file_path", f.toString(),
                "old_string", "hello world",
                "new_string", "hi world"
        ), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isFalse();
        assertThat(Files.readString(f)).isEqualTo("hi world\n");
        assertThat(res.attachments()).isNotEmpty();
    }

    @Test
    void ambiguousMatchRejected(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "x x x\n");
        Tool t = FileEditTool.build();
        Tool.ToolResult res = t.call(Map.of(
                "file_path", f.toString(),
                "old_string", "x",
                "new_string", "y"
        ), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isTrue();
        assertThat(res.output().toString()).contains("matches 3 places");
    }

    @Test
    void replaceAllReplacesAll(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "x x x\n");
        Tool t = FileEditTool.build();
        Tool.ToolResult res = t.call(Map.of(
                "file_path", f.toString(),
                "old_string", "x",
                "new_string", "y",
                "replace_all", true
        ), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isFalse();
        assertThat(Files.readString(f)).isEqualTo("y y y\n");
    }
}
