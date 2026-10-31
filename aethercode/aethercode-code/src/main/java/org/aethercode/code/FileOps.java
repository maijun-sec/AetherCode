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

package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * File operations surface.
 *
 * <p>Java-native port of the Python {@code deepagents_code.file_ops}
 * module. The Java port exposes the small set of file-tool helpers used
 * by the agent graph and the TUI's read-only inspection paths.</p>
 */
public final class FileOps {
    private FileOps() {}

    private static final Logger LOG = LoggerFactory.getLogger(FileOps.class);

    /** Result of a single file write. */
    public record WriteResult(boolean ok, String error) {}

    /** Read a text file. */
    public static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.debug("Could not read {}: {}", path, e.toString());
            return null;
        }
    }

    /** Write a text file. */
    public static WriteResult writeFile(Path path, String content) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return new WriteResult(true, null);
        } catch (IOException e) {
            return new WriteResult(false, e.getMessage());
        }
    }

    /** Edit a file by replacing oldString with newString. */
    public static WriteResult editFile(Path path, String oldString, String newString) {
        String text = readFile(path);
        if (text == null) return new WriteResult(false, "file not found");
        if (!text.contains(oldString)) return new WriteResult(false, "old_string not found");
        String updated = text.replace(oldString, newString);
        return writeFile(path, updated);
    }

    /** Whether a path looks like a missing-file error. */
    public static boolean isFileNotFoundError(Object error) {
        if (error == null) return false;
        String s = error.toString();
        return s.contains("NoSuchFile") || s.contains("FileNotFound");
    }
}
