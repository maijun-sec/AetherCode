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

package org.aethercode.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The {@code MEMORY.md} entrypoint — a small, hard-capped index file that lives at the root
 * of an agent memory directory. Modelled after the TS {@code memdir/memdir.ts}.
 *
 * <p>The entrypoint is the only memory content the model sees by default. The model is
 * expected to add one-line index entries here that point to topic files in the same
 * directory. The full topic content is only loaded when the model chooses to read the file.
 *
 * <p>Hard cap: 200 lines / 25 KiB. Anything beyond is truncated on read so a runaway model
 * cannot blow the system prompt.
 */
public final class MemoryEntrypoint {

    private MemoryEntrypoint() {}

    /** Read the entrypoint; truncate if it exceeds the cap. */
    public static String readTruncated(Path file) {
        if (!Files.exists(file)) return "";
        try {
            String content = Files.readString(file);
            Truncated t = truncate(content);
            return t.content;
        } catch (IOException e) {
            return "";
        }
    }

    public static Truncated truncate(String content) {
        if (content == null) return new Truncated("", 0, 0, false, false);
        String[] lines = content.split("\n", -1);
        boolean lineTruncated = lines.length > MemoryPaths.MAX_ENTRYPOINT_LINES;
        int keep = Math.min(lines.length, MemoryPaths.MAX_ENTRYPOINT_LINES);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            sb.append(lines[i]);
            if (i < keep - 1) sb.append('\n');
        }
        String out = sb.toString();
        boolean byteTruncated = out.getBytes().length > MemoryPaths.MAX_ENTRYPOINT_BYTES;
        if (byteTruncated) {
            byte[] b = out.getBytes();
            out = new String(b, 0, MemoryPaths.MAX_ENTRYPOINT_BYTES);
        }
        return new Truncated(out, out.getBytes().length, keep, lineTruncated, byteTruncated);
    }

    public static void ensureDir(Path dir) {
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
    }

    public record Truncated(
            String content,
            int byteCount,
            int lineCount,
            boolean wasLineTruncated,
            boolean wasByteTruncated
    ) {}
}
