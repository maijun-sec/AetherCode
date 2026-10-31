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
import static org.assertj.core.api.Assumptions.assumeThat;

class FileReadToolTest {

    /** the surefire plugin in aethercode-tools/pom.xml
     *  sets {@code aethercode.cwd=java.io.tmpdir} for every
     *  test JVM. That makes {@code @TempDir} subdirs land
     *  inside the sandbox automatically. The
     *  {@code readOutsideSandboxRefused} test installs its
     *  own tighter sandbox via the same property. */

    @Test
    void readsFileWithLineNumbers(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("hello.txt");
        Files.writeString(f, "first\nsecond\nthird\n");
        Tool t = FileReadTool.build();
        Tool.ToolResult res = t.call(Map.of("file_path", f.toString()), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isFalse();
        String out = (String) res.output();
        assertThat(out).contains("1\tfirst");
        assertThat(out).contains("2\tsecond");
        assertThat(out).contains("3\tthird");
    }

    @Test
    void rejectsBinaryFile(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("blob.bin");
        byte[] data = new byte[4096];
        for (int i = 0; i < data.length; i++) data[i] = 0; // all NUL
        Files.write(f, data);
        Tool t = FileReadTool.build();
        Tool.ToolResult res = t.call(Map.of("file_path", f.toString()), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isTrue();
        assertThat(res.output().toString()).contains("binary");
    }

    /** read of a file outside the sandbox cwd is refused.
     *  We install a tight sandbox cwd = the test's own temp
     *  dir, then read a sibling file in the temp parent. The
     *  parent path is a real Windows temp dir
     *  ({@code C:\Users\xxx\AppData\Local\Temp\}) and is
     *  outside the per-test temp subdir. */
    @Test
    void readOutsideSandboxRefused(@TempDir Path tmp) throws Exception {
        // R-paper-batch7-tools-sandbox: this test installs its own
        // tighter sandbox via the aethercode.cwd system property.
        // It depends on the sandbox check actually firing — if the
        // environment exports AETHERCODE_ALLOW_ANY_PATH=1 (used for
        // local development / CI to opt out of the sandbox) the
        // check is bypassed and this test fails. We assert the env
        // var is NOT set in the test JVM so the test is hermetic.
        assumeThat(System.getenv("AETHERCODE_ALLOW_ANY_PATH"))
            .as("AETHERCODE_ALLOW_ANY_PATH must not be set for sandbox tests to be meaningful")
            .isNull();
        String savedCwd = System.getProperty("aethercode.cwd");
        try {
            // Tighten the sandbox to JUST the test's temp dir
            // so the parent (java.io.tmpdir) is now outside.
            System.setProperty("aethercode.cwd", tmp.toAbsolutePath().normalize().toString());
            Path outside = tmp.getParent().resolve("aethercode-outside-" + System.nanoTime() + ".txt");
            Files.writeString(outside, "secret");
            try {
                Tool t = FileReadTool.build();
                Tool.ToolResult res = t.call(Map.of("file_path", outside.toString()), Tool.CallContext.of("s")).join();
                assertThat(res.isError()).isTrue();
                assertThat(res.output().toString()).contains("outside the working directory");
            } finally {
                Files.deleteIfExists(outside);
            }
        } finally {
            if (savedCwd == null) {
                System.clearProperty("aethercode.cwd");
            } else {
                System.setProperty("aethercode.cwd", savedCwd);
            }
        }
    }

    @Test
    void readsPngByExtension(@TempDir Path tmp) throws Exception {
        // 1x1 transparent PNG (67 bytes — the standard tiny PNG).
        Path f = tmp.resolve("dot.png");
        Files.write(f, new byte[]{
                (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
                0, 0, 0, 13, 'I', 'H', 'D', 'R',
                0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0,
                31, 21, (byte) 0xC4, (byte) 0x89,
                0, 0, 0, 13, 'I', 'D', 'A', 'T',
                78, (byte) 0xDA, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) 0xC0, (byte) 0xFE, (byte) 0xA3, 0x42, 0, (byte) 0x80,
                0, 0, 0, 0, 'I', 'E', 'N', 'D',
                (byte) 0xAE, 0x42, 0x60, (byte) 0x82
        });
        Tool t = FileReadTool.build();
        Tool.ToolResult res = t.call(Map.of("file_path", f.toString()), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isFalse();
        // Text body describes the image.
        String out = (String) res.output();
        assertThat(out).contains("image/png");
        // The attachment carries the base64-encoded bytes.
        assertThat(res.attachments()).hasSize(1);
        var att = res.attachments().get(0);
        assertThat(att).isInstanceOf(Tool.Attachment.ImageAttachment.class);
        var img = (Tool.Attachment.ImageAttachment) att;
        assertThat(img.mimeType()).isEqualTo("image/png");
        assertThat(img.byteCount()).isEqualTo(71);
        assertThat(img.base64()).isNotEmpty();
    }

    @Test
    void detectsPngByMagicBytesEvenWithWrongExtension(@TempDir Path tmp) throws Exception {
        // Same PNG, but saved with a .bin extension. The tool
        // should still recognise it as image/png and return the
        // attachment. This is the "renamed file" case.
        Path f = tmp.resolve("not-really.bin");
        Files.write(f, new byte[]{
                (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
                0, 0, 0, 13, 'I', 'H', 'D', 'R',
                0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0,
                31, 21, (byte) 0xC4, (byte) 0x89,
                0, 0, 0, 13, 'I', 'D', 'A', 'T',
                78, (byte) 0xDA, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) 0xC0, (byte) 0xFE, (byte) 0xA3, 0x42, 0, (byte) 0x80,
                0, 0, 0, 0, 'I', 'E', 'N', 'D',
                (byte) 0xAE, 0x42, 0x60, (byte) 0x82
        });
        Tool t = FileReadTool.build();
        Tool.ToolResult res = t.call(Map.of("file_path", f.toString()), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isFalse();
        String out = (String) res.output();
        assertThat(out).contains("image/png");
        assertThat(res.attachments()).hasSize(1);
    }

    @Test
    void detectImageMime_returnsMimeForKnownTypes(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("a.png");
        Files.write(png, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        assertThat(FileReadTool.detectImageMime(png)).isEqualTo("image/png");
        Path jpg = tmp.resolve("b.jpg");
        Files.write(jpg, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        assertThat(FileReadTool.detectImageMime(jpg)).isEqualTo("image/jpeg");
        Path gif = tmp.resolve("c.gif");
        Files.write(gif, new byte[]{'G', 'I', 'F', '8', '9', 'a'});
        assertThat(FileReadTool.detectImageMime(gif)).isEqualTo("image/gif");
        Path txt = tmp.resolve("d.txt");
        Files.writeString(txt, "hello");
        assertThat(FileReadTool.detectImageMime(txt)).isNull();
    }

    @Test
    void offsetAndLimitSlice(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("lines.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 100; i++) sb.append("line-").append(i).append('\n');
        Files.writeString(f, sb.toString());
        Tool t = FileReadTool.build();
        Tool.ToolResult res = t.call(Map.of("file_path", f.toString(), "offset", 9, "limit", 3), Tool.CallContext.of("s")).join();
        String out = (String) res.output();
        assertThat(out).contains("line-10").contains("line-11").contains("line-12");
        assertThat(out).doesNotContain("line-9").doesNotContain("line-13");
    }
}
