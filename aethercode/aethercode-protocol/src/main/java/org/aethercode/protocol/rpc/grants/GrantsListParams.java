package org.aethercode.protocol.rpc.grants;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-18 / design.md §3.1): params for {@code grants/list}.
 *
 * <p>Wire format:
 * <pre>
 *   { scope?, category?, sessionId? }
 * </pre>
 *
 * <ul>
 *   <li>{@code scope} — {@code "user"} | {@code "project"} |
 *       {@code "session"}. When absent, all three scopes are
 *       returned (the default for the Permissions settings
 *       page).</li>
 *   <li>{@code category} — restrict to a single tool category
 *       (e.g. {@code "bash"}, {@code "file.read"}).</li>
 *   <li>{@code sessionId} — required when {@code scope == "session"}.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GrantsListParams(
        @JsonProperty("scope") String scope,
        @JsonProperty("category") String category,
        @JsonProperty("sessionId") String sessionId
) {}
