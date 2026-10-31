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

package org.aethercode.code.tui.modals;

import java.util.List;
import java.util.Map;

/**
 * Cold-cache modal descriptor.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.tui.modals.cold_cache} module. The Java
 * port produces a data record the host can render; the
 * text-rendering logic is left to the consumer.</p>
 */
public final class ColdCache {
    private ColdCache() {}

    /** A single cold-cache entry. */
    public record Entry(String path, long sizeBytes, String reason) {}

    /** Modal payload. */
    public record Modal(
            String title,
            String body,
            List<Entry> entries,
            List<String> actions) {
    }

    /**
     * Build a modal payload.
     */
    public static Modal build(List<Map<String, Object>> entries) {
        List<Entry> mapped = entries.stream()
                .map(e -> new Entry(
                        String.valueOf(e.getOrDefault("path", "")),
                        ((Number) e.getOrDefault("size", 0)).longValue(),
                        String.valueOf(e.getOrDefault("reason", ""))))
                .toList();
        return new Modal(
                "Cold cache detected",
                "Some files were not loaded into the context. Continue?",
                mapped,
                List.of("continue", "abort"));
    }
}
