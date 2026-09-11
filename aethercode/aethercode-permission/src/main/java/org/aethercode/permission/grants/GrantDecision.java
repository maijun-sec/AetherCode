package org.aethercode.permission.grants;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * T-201 / design.md §3.1.1: the two terminal decisions a grant
 * records. A {@code DENY} wins over any {@code ALLOW} during
 * resolution (see {@link GrantResolver}).
 *
 * <p>Jackson is configured to use the lower-case wire format on
 * both write and read; see {@link GrantScope} for the rationale.
 */
public enum GrantDecision {
    ALLOW("allow"),
    DENY("deny");

    private final String wire;

    GrantDecision(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static GrantDecision fromWire(String s) {
        if (s == null) {
            throw new IllegalArgumentException("decision is null");
        }
        for (GrantDecision v : values()) {
            if (v.wire.equals(s)) return v;
        }
        throw new IllegalArgumentException("unknown grant decision: " + s);
    }
}
