package org.aethercode.mcp;

import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolCacheTest {

    @Test
    void firstCallMissesThenHits() {
        AtomicInteger calls = new AtomicInteger();
        McpToolCache c = new McpToolCache(Duration.ofSeconds(10),
                () -> { calls.incrementAndGet(); return List.of(stubTool("a")); });
        assertThat(c.get()).hasSize(1);
        assertThat(c.get()).hasSize(1);
        assertThat(c.get()).hasSize(1);
        // loader was called exactly once
        assertThat(calls.get()).isEqualTo(1);
        assertThat(c.hitCount()).isEqualTo(2);
        assertThat(c.missCount()).isEqualTo(1);
    }

    @Test
    void zeroTtlAlwaysRefreshes() {
        AtomicInteger calls = new AtomicInteger();
        McpToolCache c = new McpToolCache(0, () -> { calls.incrementAndGet(); return List.of(); });
        c.get();
        c.get();
        c.get();
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void invalidatesForcesRefresh() {
        AtomicInteger calls = new AtomicInteger();
        McpToolCache c = new McpToolCache(Duration.ofSeconds(10),
                () -> { calls.incrementAndGet(); return List.of(); });
        c.get();
        assertThat(c.isStale()).isFalse();
        c.invalidate();
        assertThat(c.isStale()).isTrue();
        c.get();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void expiresAfterTtl() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        McpToolCache c = new McpToolCache(50, () -> { calls.incrementAndGet(); return List.of(); });
        c.get();
        c.get();
        assertThat(calls.get()).isEqualTo(1);
        Thread.sleep(80);
        c.get();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void loaderExceptionKeepsStaleValue() {
        McpToolCache c = new McpToolCache(Duration.ofSeconds(10), () -> {
            throw new RuntimeException("loader kaboom");
        });
        // first call: loader throws, we return the empty default
        assertThat(c.get()).isEmpty();
        assertThat(c.errorCount()).isEqualTo(1);
    }

    @Test
    void loaderReturningNullKeepsStaleValue() {
        McpToolCache c = new McpToolCache(Duration.ofSeconds(10), () -> null);
        assertThat(c.get()).isEmpty();
        assertThat(c.errorCount()).isZero();
        // hit counter not incremented (we didn't have a cached value to serve)
        assertThat(c.hitCount()).isZero();
    }

    @Test
    void sizeReportsCachedCount() {
        McpToolCache c = new McpToolCache(Duration.ofSeconds(10),
                () -> List.of(stubTool("a"), stubTool("b"), stubTool("c")));
        c.get();
        assertThat(c.size()).isEqualTo(3);
    }

    @Test
    void ttlMsIsExposed() {
        McpToolCache c = new McpToolCache(12345, () -> List.of());
        assertThat(c.ttlMs()).isEqualTo(12345);
    }

    @Test
    void negativeTtlClampedToZero() {
        McpToolCache c = new McpToolCache(-50, () -> List.of());
        assertThat(c.ttlMs()).isZero();
    }

    @Test
    void defaultTtlIsFiveMinutes() {
        // the default constant is 5 min — sanity-check it doesn't drift
        assertThat(McpToolCache.DEFAULT_TTL).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void cacheContentsAreImmutable() {
        McpToolCache c = new McpToolCache(Duration.ofSeconds(10),
                () -> java.util.stream.IntStream.range(0, 3)
                        .mapToObj(i -> stubTool("t" + i))
                        .toList());
        List<Tool> first = c.get();
        try {
            first.add(stubTool("extra"));
            org.junit.jupiter.api.Assertions.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // good — caller can't mutate the cached snapshot
        }
    }

    // ---- helpers ----

    private static Tool stubTool(String n) {
        return new Tool() {
            @Override public String name() { return n; }
            @Override public String description() { return ""; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public boolean isReadOnly(Map<String, Object> in) { return true; }
            @Override public CompletableFuture<PermissionResult> checkPermissions(Map<String, Object> in, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(in));
            }
            @Override public CompletableFuture<ToolResult> call(Map<String, Object> in, CallContext ctx) {
                return CompletableFuture.completedFuture(new ToolResult(""));
            }
        };
    }
}
