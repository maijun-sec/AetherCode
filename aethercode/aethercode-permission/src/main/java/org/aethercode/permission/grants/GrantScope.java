package org.aethercode.permission.grants;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * T-201 / design.md §3.1.1: the three grant scopes.
 *
 * <p>Resolution order on a tool call (see
 * {@link org.aethercode.permission.grants.GrantResolver}) is
 * {@code session > project > user} — a session deny wins over a
 * project allow wins over a user allow.
 *
 * <p>Jackson is configured to use the lower-case wire format on
 * both write ({@link JsonValue} → {@link #wire()}) and read
 * ({@link JsonCreator} → {@link #fromWire(String)}). The default
 * enum behavior (uppercase {@code name()}) would produce files
 * that look right but don't match the spec.
 */
public enum GrantScope {
    SESSION("session"),
    PROJECT("project"),
    USER("user");

    private final String wire;

    GrantScope(String wire) {
        this.wire = wire;
    }

    /** Lower-case token used in the on-disk JSON. */
    @JsonValue
    public String wire() {
        return wire;
    }

    /** Parse the lower-case token back to the enum. Case-sensitive
     *  on the wire format: design.md §3.1.1 specifies the exact
     *  spelling. */
    @JsonCreator
    public static GrantScope fromWire(String s) {
        if (s == null) {
            throw new IllegalArgumentException("scope is null");
        }
        for (GrantScope v : values()) {
            if (v.wire.equals(s)) return v;
        }
        throw new IllegalArgumentException("unknown grant scope: " + s);
    }
}
