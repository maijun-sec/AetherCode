package org.aethercode.protocol.rpc.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-15 / design.md §3.1): result for {@code task/kill}.
 *
 * <p>Wire format:
 * <pre>
 *   { "id": "...", "status": "KILLED", "ok": true,
 *     "noop": false }
 * </pre>
 *
 * <p>{@code noop} is true when the task was already in a
 * terminal state; the renderer can use it to skip a toast
 * like "Session killed" the second time the user mashes the
 * Stop button.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskKillResult(
        @JsonProperty("id") String id,
        @JsonProperty("status") String status,
        @JsonProperty("ok") boolean ok,
        @JsonProperty("noop") boolean noop
) {}
