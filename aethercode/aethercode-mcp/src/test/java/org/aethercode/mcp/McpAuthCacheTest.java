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

package org.aethercode.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class McpAuthCacheTest {

    @Test
    void marksAndRespectsExpiry(@TempDir Path tmp) {
        Path f = tmp.resolve("cache.json");
        McpAuthCache cache = new McpAuthCache(f);
        cache.markAuthNeeded("server-1");
        assertThat(cache.isAuthNeeded("server-1")).isTrue();
        cache.clear("server-1");
        assertThat(cache.isAuthNeeded("server-1")).isFalse();
    }

    @Test
    void survivesRoundTrip(@TempDir Path tmp) {
        Path f = tmp.resolve("cache.json");
        McpAuthCache first = new McpAuthCache(f);
        first.markAuthNeeded("server-a");
        // simulate a process restart
        McpAuthCache second = new McpAuthCache(f);
        assertThat(second.isAuthNeeded("server-a")).isTrue();
        assertThat(second.isAuthNeeded("server-b")).isFalse();
    }

    @Test
    void clearAllEmptiesCache(@TempDir Path tmp) {
        Path f = tmp.resolve("cache.json");
        McpAuthCache cache = new McpAuthCache(f);
        cache.markAuthNeeded("a");
        cache.markAuthNeeded("b");
        cache.clearAll();
        assertThat(cache.isAuthNeeded("a")).isFalse();
        assertThat(cache.isAuthNeeded("b")).isFalse();
    }
}
