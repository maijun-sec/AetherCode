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
