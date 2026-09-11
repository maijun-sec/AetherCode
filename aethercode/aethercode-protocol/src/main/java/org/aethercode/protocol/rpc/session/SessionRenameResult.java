package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-11 / design.md §3.1): result for {@code session/rename}.
 *
 * <p>Wire format:
 * <pre>
 *   { "id": "...", "title": "..." }
 * </pre>
 *
 * <p>Returns the new title so the renderer can update its
 * local cache without a follow-up show call. The {@code id}
 * echoes the request id.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionRenameResult(
        @JsonProperty("id") String id,
        @JsonProperty("title") String title
) {}
