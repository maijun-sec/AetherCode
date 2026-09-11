package org.aethercode.permission.grants;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * T-201 / design.md §3.1.1: a single consent grant.
 *
 * <pre>
 * type Grant = {
 *   id: string;                 // uuid
 *   scope: "session" | "project" | "user";
 *   scopeId: string;            // session_id | project_id | "global"
 *   category: string;           // e.g. "shell.command.npm_install"
 *   decision: "allow" | "deny";
 *   reason: string;
 *   createdAt: number;          // epoch ms
 *   expiresAt?: number;         // optional TTL
 * };
 * </pre>
 *
 * <p>Backed by a Java record. Field order in JSON is preserved for
 * human readability via {@link JsonPropertyOrder}.
 *
 * <p>Unknown JSON properties are ignored on read so the file can
 * grow new optional fields (e.g. {@code grantedBy}, {@code notes})
 * without breaking older clients.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "scope", "scopeId", "category", "decision", "reason", "createdAt", "expiresAt"})
public record Grant(
        @JsonProperty("id") String id,
        @JsonProperty("scope") GrantScope scope,
        @JsonProperty("scopeId") String scopeId,
        @JsonProperty("category") String category,
        @JsonProperty("decision") GrantDecision decision,
        @JsonProperty("reason") String reason,
        @JsonProperty("createdAt") long createdAt,
        @JsonProperty("expiresAt") Long expiresAt
) {

    @JsonCreator
    public Grant {
        // Compact constructor: validate the fields Jackson didn't get to
        // (Jackson does type coercion for enums via the static factories
        // on GrantScope / GrantDecision, but it can't check non-null
        // string invariants).
        Objects.requireNonNull(id, "grant.id");
        Objects.requireNonNull(scope, "grant.scope");
        Objects.requireNonNull(scopeId, "grant.scopeId");
        Objects.requireNonNull(category, "grant.category");
        Objects.requireNonNull(decision, "grant.decision");
        Objects.requireNonNull(reason, "grant.reason");
        if (id.isBlank()) throw new IllegalArgumentException("grant.id is blank");
        if (scopeId.isBlank()) throw new IllegalArgumentException("grant.scopeId is blank");
        if (category.isBlank()) throw new IllegalArgumentException("grant.category is blank");
        if (createdAt <= 0L) throw new IllegalArgumentException("grant.createdAt must be > 0");
        if (expiresAt != null && expiresAt <= 0L) {
            throw new IllegalArgumentException("grant.expiresAt must be > 0 when present");
        }
    }

    /** True if {@code now} (epoch ms) is past the TTL. Expired
     *  grants are not active — {@link GrantResolver} filters them
     *  out before deny-wins resolution. A null TTL means "never
     *  expires". */
    public boolean isExpired(long now) {
        return expiresAt != null && now >= expiresAt;
    }

    /** Convenience builder that auto-fills {@code id} (random UUID
     *  hex) and {@code createdAt} (now) so the common case of
     *  appending a fresh grant from user input is one line. */
    public static Grant create(
            GrantScope scope,
            String scopeId,
            String category,
            GrantDecision decision,
            String reason,
            Long expiresAt
    ) {
        return new Grant(
                newId(),
                scope,
                scopeId,
                category,
                decision,
                reason == null ? "" : reason,
                System.currentTimeMillis(),
                expiresAt
        );
    }

    /** 32-char hex UUID, no dashes. We avoid java.util.UUID's dashes
     *  to match the spec example and to keep the on-disk token
     *  opaque to any grep that pattern-matches for "uuid-". */
    public static String newId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
