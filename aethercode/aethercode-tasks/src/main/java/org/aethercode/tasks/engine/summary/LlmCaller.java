package org.aethercode.tasks.engine.summary;

import java.util.List;
import java.util.Map;

/**
 * Minimal LLM call abstraction for the post-turn summary hook.
 * The real implementation lives in
 * {@code aethercode-llm} / {@code deepagents-llm}; the hook
 * only needs {@code complete(systemPrompt, userPrompt)}.
 */
public interface LlmCaller {

    /**
     * One-shot completion. Implementations should be
     * non-blocking (return a {@code String} directly when
     * synchronous, or wrap in a {@code CompletableFuture} for
     * the caller).
     */
    String complete(String systemPrompt, String userPrompt);

    /** Convenience for richer message histories. */
    default String complete(String systemPrompt, List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : messages) {
            Object role = m.getOrDefault("role", "user");
            Object content = m.getOrDefault("content", "");
            sb.append('[').append(role).append("]\n").append(content).append("\n\n");
        }
        return complete(systemPrompt, sb.toString());
    }
}
