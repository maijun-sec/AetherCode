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
 * File contents + metadata.
 *
 * <p>Mirror of the deepagents <code>FileData</code> TypedDict: a string
 * <code>content</code> (utf-8 or base64) plus an <code>encoding</code>
 * tag and optional timestamps. Stored as the value type in
 * {@code StateBackend}'s files map.</p>
 */
public final class FileData {
    public static final String ENCODING_UTF8   = "utf-8";
    public static final String ENCODING_BASE64 = "base64";

    private final String content;
    private final String encoding;
    private final String createdAt;
    private final String modifiedAt;

    private FileData(String content, String encoding, String createdAt, String modifiedAt) {
        this.content    = content;
        this.encoding   = encoding == null ? ENCODING_UTF8 : encoding;
        this.createdAt  = createdAt  == null ? "" : createdAt;
        this.modifiedAt = modifiedAt == null ? "" : modifiedAt;
    }

    public static FileData of(String content) {
        return new FileData(content, ENCODING_UTF8, "", "");
    }

    public static FileData of(String content, String encoding) {
        return new FileData(content, encoding, "", "");
    }

    public static FileData of(String content, String encoding, String createdAt, String modifiedAt) {
        return new FileData(content, encoding, createdAt, modifiedAt);
    }

    public String content()                                { return content; }
    public String encoding()                               { return encoding; }
    public Optional<String> createdAtOpt()                 { return createdAt.isEmpty()  ? Optional.empty() : Optional.of(createdAt); }
    public Optional<String> modifiedAtOpt()                { return modifiedAt.isEmpty() ? Optional.empty() : Optional.of(modifiedAt); }
    public boolean isBinary()                              { return ENCODING_BASE64.equals(encoding); }

    public FileData withContent(String newContent) {
        return new FileData(newContent, encoding, createdAt, java.time.Instant.now().toString());
    }

    public FileData withCreatedAt(String ts) {
        return new FileData(content, encoding, ts, modifiedAt);
    }

    public FileData withModifiedAt(String ts) {
        return new FileData(content, encoding, createdAt, ts);
    }

    /** Convert to a Map (parity with the Python TypedDict wire format). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("content", content);
        m.put("encoding", encoding);
        if (!createdAt.isEmpty())  m.put("created_at",  createdAt);
        if (!modifiedAt.isEmpty()) m.put("modified_at", modifiedAt);
        return m;
    }
}
