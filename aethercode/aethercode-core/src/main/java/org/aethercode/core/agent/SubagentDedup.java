package org.aethercode.core.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * a small dedup layer for subagent submissions. If a
 * task with the same signature is submitted while the previous
 * one is still running or has just completed, the dedup layer
 * returns the existing agentId instead of dispatching a new
 * one. Useful for plans that retry the same step.
 *
 * <p>Signature format: {@code <description>:<first 64 chars of
 * task.toString()>}. The task's toString is included so two
 * tasks with the same description but different inputs are
 * considered distinct.
 *
 * <p>This is a per-pool cache, not a global one — different
 * pools have different dedup windows.
 */
public final class SubagentDedup {

    private final Map<String, Entry> cache;
    private final int maxEntries;

    public SubagentDedup() { this(64); }

    public SubagentDedup(int maxEntries) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be >= 1");
        this.maxEntries = maxEntries;
        this.cache = new LinkedHashMap<>(16, 0.75f, true); // access-order LRU
    }

    public record Entry(String agentId, String signature, long submittedAtMs) {}

    /** try to find an existing entry for the given
     *  description+task. If found, returns its agentId. If not,
     *  records a placeholder (to be filled in by recordActual). */
    public synchronized Optional<String> tryReuse(String description, Object taskHint) {
        String sig = signatureOf(description, taskHint);
        Entry e = cache.get(sig);
        if (e != null) return Optional.of(e.agentId());
        cache.put(sig, new Entry(null, sig, System.currentTimeMillis()));
        trim();
        return Optional.empty();
    }

    /** record the actual agentId assigned for a
     *  submission, so future dedup hits can return it. */
    public synchronized void recordActual(String description, Object taskHint, String agentId) {
        Objects.requireNonNull(agentId, "agentId");
        String sig = signatureOf(description, taskHint);
        cache.put(sig, new Entry(agentId, sig, System.currentTimeMillis()));
        trim();
    }

    public synchronized int size() { return cache.size(); }
    public synchronized void clear() { cache.clear(); }

    public static String signatureOf(String description, Object taskHint) {
        String hint = taskHint == null ? "" : taskHint.toString();
        if (hint.length() > 64) hint = hint.substring(0, 64);
        return (description == null ? "" : description) + ":" + hint;
    }

    private void trim() {
        while (cache.size() > maxEntries) {
            var it = cache.entrySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
    }
}
