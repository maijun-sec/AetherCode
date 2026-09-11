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
