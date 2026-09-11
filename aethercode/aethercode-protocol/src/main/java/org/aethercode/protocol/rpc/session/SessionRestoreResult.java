package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-12 / design.md §3.1): result for {@code session/restore}.
 *
 * <p>Wire format:
 * <pre>
 *   { "id": "...", "trashed": false }
 * </pre>
 *
 * <p>{@code trashed: false} echoes the post-restore state so the
 * renderer can drop the row from the trash view without a
 * follow-up list call.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionRestoreResult(
        @JsonProperty("id") String id,
        @JsonProperty("trashed") boolean trashed
) {}
