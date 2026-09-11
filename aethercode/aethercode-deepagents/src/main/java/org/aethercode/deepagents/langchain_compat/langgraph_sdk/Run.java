package org.aethercode.deepagents.langchain_compat.langgraph_sdk;

import java.util.Map;
import java.util.Objects;

/**
 * LangGraph SDK {@code Run} record.
 *
 * <p>Java-native port of
 * {@code langgraph_sdk.schema.Run}. Mirrors the Python TypedDict
 * shape: a run is identified by a run id, thread id, status, and
 * carries the input/output payloads and timestamps.</p>
 */
public record Run(
        String runId,
        String threadId,
        String status,
        Map<String, Object> input,
        Map<String, Object> output,
        String error) {

    public Run {
        runId = Objects.requireNonNull(runId, "runId");
        threadId = Objects.requireNonNull(threadId, "threadId");
        status = status == null ? "pending" : status;
        input = input == null ? Map.of() : Map.copyOf(input);
        output = output == null ? Map.of() : Map.copyOf(output);
    }

    public boolean isTerminal() {
        return switch (status) {
            case "success", "error", "timeout", "interrupted" -> true;
            default -> false;
        };
    }
}
