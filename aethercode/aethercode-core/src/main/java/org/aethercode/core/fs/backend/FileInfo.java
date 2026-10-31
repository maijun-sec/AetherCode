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

package org.aethercode.core.fs.backend;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Structured file listing info.
 *
 * <p>Mirror of the deepagents <code>FileInfo</code> TypedDict: only
 * <code>path</code> is required, the rest is best-effort per backend.</p>
 */
public final class FileInfo {
    private final String path;
    private final boolean isDir;
    private final long size;
    private final String modifiedAt;

    private FileInfo(String path, boolean isDir, long size, String modifiedAt) {
        this.path = path;
        this.isDir = isDir;
        this.size = size;
        this.modifiedAt = modifiedAt == null ? "" : modifiedAt;
    }

    public static FileInfo file(String path) {
        return new FileInfo(path, false, 0L, "");
    }

    public static FileInfo file(String path, long size, String modifiedAt) {
        return new FileInfo(path, false, size, modifiedAt);
    }

    public static FileInfo directory(String path) {
        return new FileInfo(path, true, 0L, "");
    }

    public String path()                      { return path; }
    public boolean isDir()                    { return isDir; }
    public long size()                        { return size; }
    public Optional<String> modifiedAtOpt()   { return modifiedAt.isEmpty() ? Optional.empty() : Optional.of(modifiedAt); }

    /** Convert to a Map (parity with the Python TypedDict wire format). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("path", path);
        if (isDir) m.put("is_dir", true);
        if (size != 0L) m.put("size", size);
        if (!modifiedAt.isEmpty()) m.put("modified_at", modifiedAt);
        return m;
    }

    @Override
    public String toString() {
        return isDir ? path + "/" : path;
    }
}
