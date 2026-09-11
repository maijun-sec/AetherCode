package org.aethercode.code;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Testing models (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._testing_models}
 * module. The Java port exposes the surface the integration tests use
 * to drive the agent graph with deterministic responses; the full
 * implementation lands with the deepagents-core test fixtures.</p>
 */
public final class TestingModels {
    private TestingModels() {}

    /** A scripted response. */
    public record ScriptedResponse(String text, List<String> toolCalls) {
        public ScriptedResponse(String text) { this(text, List.of()); }
    }

    private final List<ScriptedResponse> queue = new ArrayList<>();

    /** Append a scripted response. */
    public void enqueue(ScriptedResponse response) {
        if (response != null) queue.add(response);
    }

    /** Take the next scripted response, or return an empty one. */
    public ScriptedResponse take() {
        if (queue.isEmpty()) return new ScriptedResponse("", List.of());
        return queue.remove(0);
    }

    /** Whether there are no queued responses. */
    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
