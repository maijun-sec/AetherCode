package org.aethercode.protocol.rpc.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Phase 1.2 (T-1-16 / design.md §3.1): params for {@code task/list}
 * (refined — supports multi-status filter + pagination).
 *
 * <p>Wire format:
 * <pre>
 *   { state?, states?, limit?, offset? }
 * </pre>
 *
 * <p>Either {@code state} (single value, v1 compatible) or
 * {@code states} (multi-value) may be passed. With both absent
 * the server returns every task.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskListParams(
        @JsonProperty("state") String state,
        @JsonProperty("states") List<String> states,
        @JsonProperty("limit") Integer limit,
        @JsonProperty("offset") Integer offset
) {
    public List<String> effectiveStates() {
        if (states != null && !states.isEmpty()) return states;
        if (state != null && !state.isBlank()) return List.of(state);
        return null;
    }
}
