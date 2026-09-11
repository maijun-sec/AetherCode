package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-12 / design.md §3.1): result for {@code session/resume}.
 *
 * <p>Wire format:
 * <pre>
 *   { "id": "...", "status": "RUNNING", "ok": true }
 * </pre>
 *
 * <p>The transition is from {@code PAUSED} / {@code COMPLETED}
 * / {@code FAILED} → {@code RUNNING}. A session already in
 * {@code RUNNING} returns {@code ALREADY_EXISTS} (or
 * {@code noop: true} in the success case — see the dispatcher
 * wiring).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionResumeResult(
        @JsonProperty("id") String id,
        @JsonProperty("status") String status,
        @JsonProperty("ok") boolean ok
) {}
