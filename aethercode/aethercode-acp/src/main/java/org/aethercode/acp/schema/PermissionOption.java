package org.aethercode.acp.schema;

import java.util.Objects;

/**
 * ACP permission request option.
 *
 * <p>Mirrors {@code acp.schema.PermissionOption}. The
 * {@code kind} is one of {@code "allow_once"},
 * {@code "allow_always"}, {@code "reject_once"},
 * {@code "reject_always"}; the Java port keeps the wire value
 * as a {@code String} and provides a {@link Kind} enum for
 * type-safe checks.</p>
 */
public record PermissionOption(
        String optionId,
        String name,
        String kind) {
    public PermissionOption {
        Objects.requireNonNull(optionId, "optionId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
    }

    public enum Kind {
        ALLOW_ONCE("allow_once"),
        ALLOW_ALWAYS("allow_always"),
        REJECT_ONCE("reject_once"),
        REJECT_ALWAYS("reject_always");

        private final String wire;
        Kind(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }
}
