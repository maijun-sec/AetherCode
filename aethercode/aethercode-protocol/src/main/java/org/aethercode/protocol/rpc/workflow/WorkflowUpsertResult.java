package org.aethercode.protocol.rpc.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-20 / design.md §3.1): result for {@code workflow/upsert}.
 *
 * <p>Wire format:
 * <pre>
 *   { "ok": true, "name": "...", "path": "..." }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowUpsertResult(
        @JsonProperty("ok") boolean ok,
        @JsonProperty("name") String name,
        @JsonProperty("path") String path
) {}
