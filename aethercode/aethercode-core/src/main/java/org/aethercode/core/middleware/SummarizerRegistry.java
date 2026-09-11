package org.aethercode.core.middleware;

import org.aethercode.core.runtime.Message;

import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry for {@link Summarizer} implementations.
 *
 * <p>Java-native port of the summarization engine lookup in
 * {@code deepagents.middleware.summarization}. The Java port uses
 * an in-process {@link ConcurrentMap} plus a {@link ServiceLoader}
 * fallback so a downstream consumer can register a real LLM-backed
 * summarizer via
 * {@code META-INF/services/org.aethercode.core.middleware.Summarizer}.</p>
 */
public final class SummarizerRegistry {
    private static final ConcurrentMap<String, Summarizer> SUMMARIZERS = new ConcurrentHashMap<>();

    static {
        register(new Summarizer() {
            @Override public String name() { return "not-installed"; }
            @Override public String summarize(List<Message> messages, java.util.Map<String, Object> context) {
                throw new SummarizerUnavailableError("Summarizer not configured");
            }
        });
        for (Summarizer s : ServiceLoader.load(Summarizer.class)) {
            register(s);
        }
    }

    private SummarizerRegistry() {}

    public static void register(Summarizer summarizer) {
        if (summarizer == null) return;
        SUMMARIZERS.put(summarizer.name().toLowerCase(Locale.ROOT), summarizer);
    }

    public static Summarizer get(String name) {
        if (name == null) {
            // Auto-pick the first non-default summarizer.
            return SUMMARIZERS.values().stream()
                    .filter(s -> !"not-installed".equals(s.name()))
                    .findFirst()
                    .orElseGet(() -> SUMMARIZERS.get("not-installed"));
        }
        Summarizer s = SUMMARIZERS.get(name.toLowerCase(Locale.ROOT));
        return s == null ? SUMMARIZERS.get("not-installed") : s;
    }

    public static boolean isAvailable() {
        return SUMMARIZERS.values().stream().anyMatch(s -> !"not-installed".equals(s.name()));
    }

    static void clear() {
        SUMMARIZERS.clear();
        // Re-seed the not-installed default so the registry always has
        // at least one entry.
        register(new Summarizer() {
            @Override public String name() { return "not-installed"; }
            @Override public String summarize(List<Message> messages, java.util.Map<String, Object> context) {
                throw new SummarizerUnavailableError("Summarizer not configured");
            }
        });
    }
}
