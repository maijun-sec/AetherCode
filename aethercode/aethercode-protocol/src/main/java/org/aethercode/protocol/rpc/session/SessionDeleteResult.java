package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-12 / design.md §3.1): result for {@code session/delete}.
 *
 * <p>Wire format:
 * <pre>
 *   { "id": "...", "trashed": true, "hard": false }
 * </pre>
 *
 * <p>For soft-delete (the default) {@code trashed} is true and
 * the row is still on disk in the trash. For hard-delete the
 * row is gone; the response still echoes the id so the
 * client can drop it from its local cache.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionDeleteResult(
        @JsonProperty("id") String id,
        @JsonProperty("trashed") boolean trashed,
        @JsonProperty("hard") boolean hard
) {}
