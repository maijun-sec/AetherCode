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
