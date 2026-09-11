package org.aethercode.core.middleware;

import java.util.List;
import java.util.Map;

/**
 * One summarization event recorded on the agent state.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.summarization.SummarizationState}'s
 * per-event shape. The record carries the timestamp, the model's
 * summary text, the messages that were summarized, the file
 * path the offloaded history was written to, and a free-form
 * details map.</p>
 */
public record SummarizationEvent(
        String timestamp,
        String summary,
        List<String> summarizedMessageIds,
        String historyFilePath,
        Map<String, Object> details) {

    public SummarizationEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
