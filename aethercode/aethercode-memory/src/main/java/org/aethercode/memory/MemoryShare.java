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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * a thin layer over the file-backed memory system that
 * lets a session read entries another session wrote under the
 * USER scope. Useful for cross-session knowledge sharing —
 * e.g. one session learns a project convention, the next
 * session can pick it up via a tag query.
 *
 * <p>Each entry can be tagged with a comma-separated list of
 * tags (stored in a sidecar file {@code <key>.tags}). The
 * query is "give me all entries tagged with at least one of
 * these tags" — a simple OR match.
 */
public final class MemoryShare {

    private final Path memoryDir;

    public MemoryShare(Path memoryBase, String agentType) {
        if (memoryBase == null) throw new IllegalArgumentException("memoryBase must not be null");
        if (agentType == null || agentType.isBlank())
            throw new IllegalArgumentException("agentType must not be blank");
        // USER-scope memory — cross-session.
        this.memoryDir = memoryBase.resolve("agent-memory")
                .resolve(MemoryPaths.sanitize(agentType));
    }

    public Path root() { return memoryDir; }

    /** store a tagged memory. Tags are stored in a
     *  sidecar {@code <key>.tags} file. */
    public void storeTagged(String key, String value, Set<String> tags) throws IOException {
        if (key == null || key.isBlank())
            throw new IllegalArgumentException("key must not be blank");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve(key), value, StandardCharsets.UTF_8);
        if (tags != null && !tags.isEmpty()) {
            Files.writeString(memoryDir.resolve(key + ".tags"),
                    String.join(",", tags), StandardCharsets.UTF_8);
        }
    }

    /** list all keys with the given tag. Returns the
     *  values too. */
    public Map<String, String> findByTag(String tag) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (tag == null) return out;
        if (!Files.isDirectory(memoryDir)) return out;
        String wantTag = tag.toLowerCase();
        try (var stream = Files.list(memoryDir)) {
            var tagsFiles = stream.filter(p -> p.getFileName().toString().endsWith(".tags")).toList();
            for (Path tagsFile : tagsFiles) {
                String content = Files.readString(tagsFile, StandardCharsets.UTF_8);
                if (!content.toLowerCase().contains(wantTag)) continue;
                String key = tagsFile.getFileName().toString();
                key = key.substring(0, key.length() - ".tags".length());
                Path valueFile = memoryDir.resolve(key);
                if (Files.isRegularFile(valueFile)) {
                    out.put(key, Files.readString(valueFile, StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    /** list all keys in the share (regardless of tag). */
    public List<String> allKeys() throws IOException {
        if (!Files.isDirectory(memoryDir)) return List.of();
        try (var stream = Files.list(memoryDir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.endsWith(".tags"))
                    .sorted()
                    .toList();
        }
    }
}
