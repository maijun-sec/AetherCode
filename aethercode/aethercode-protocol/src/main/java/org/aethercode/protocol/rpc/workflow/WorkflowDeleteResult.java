package org.aethercode.protocol.rpc.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-20 / design.md §3.1): result for {@code workflow/delete}.
 *
 * <p>Wire format:
 * <pre>
 *   { "ok": true, "name": "..." }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowDeleteResult(
        @JsonProperty("ok") boolean ok,
        @JsonProperty("name") String name
) {}
