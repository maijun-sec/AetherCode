package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Tool stream (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._tool_stream}
 * module. The Java port exposes the surface used to surface tool-call
 * events to the TUI as they happen; the full implementation lands
 * with the deepagents-core middleware port.</p>
 */
public final class ToolStream {
    private ToolStream() {}

    private static final Logger LOG = LoggerFactory.getLogger(ToolStream.class);

    /** A tool-stream event. */
    public record Event(
            String toolCallId,
            String toolName,
            Object args,
            Object result) {
    }

    /** Subscribe to the tool stream. Returns a no-op completion. */
    public static CompletionStage<Void> subscribe(java.util.function.Consumer<Event> listener) {
        if (listener != null) {
            LOG.debug("ToolStream.subscribe (stub)");
        }
        return CompletableFuture.completedFuture(null);
    }
}
