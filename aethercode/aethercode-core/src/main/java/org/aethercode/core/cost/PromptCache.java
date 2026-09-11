package org.aethercode.core.cost;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * tiny cache for the rendered system prompt. The system
 * prompt is built once per session and re-rendered only when one
 * of its inputs (the tool list, the memory recall, the plan mode
 * suffix) changes. The cache keys inputs by their SHA-256 hash;
 * if the inputs hash to the same value as last time, the cached
 * output is returned without re-rendering.
 *
 * <p>Why this matters: the system prompt includes the full tool
 * list with descriptions and input schemas. For a project with
 * 15+ tools, that's a few thousand tokens of overhead PER turn.
 * Re-rendering it on every turn (the old prior round behaviour) is
 * wasted work — the tools don't change mid-session.
 *
 * <p>Used by the engine's per-turn system-prompt builder. The
 * {@link #getOrRender(String, Supplier)} method takes a key
 * (typically a hash of the inputs that affect the prompt) and a
 * supplier that actually does the rendering. If the key matches a
 * cached value, the cached string is returned; otherwise the
 * supplier runs and the result is cached.
 */
public class PromptCache {

    private final ConcurrentHashMap<String, String> byKey = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong hits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong misses = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Look up {@code key} in the cache. On miss, call {@code render}
     * to build the value, store it, and return it. The render
     * supplier is called AT MOST once per key per JVM lifetime.
     */
    public String getOrRender(String key, Supplier<String> render) {
        String cached = byKey.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        misses.incrementAndGet();
        String fresh = render.get();
        byKey.put(key, fresh);
        return fresh;
    }

    /** Test helper: how many cache hits have we served. */
    public long hits() { return hits.get(); }

    /** Test helper: how many cache misses (re-renders). */
    public long misses() { return misses.get(); }

    /** Test helper: current cache size (unique keys). */
    public int size() { return byKey.size(); }

    /** Wipe the cache. Used when the user runs `/clear` or the
     *  engine is reset. */
    public void clear() {
        byKey.clear();
    }

    /** convenience — compute a SHA-256 hex digest of the
     *  concatenation of the given parts. Used to build stable
     *  cache keys from heterogeneous inputs. */
    public static String keyOf(String... parts) {
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
        for (String p : parts) {
            if (p == null) p = "";
            md.update(p.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);  // null separator so "ab|c" ≠ "a|bc"
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
