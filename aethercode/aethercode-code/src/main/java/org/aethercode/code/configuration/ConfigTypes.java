package org.aethercode.code.configuration;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Typed configuration-provider results and health metadata.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.configuration.types} module. Sealed
 * {@code ProviderResult} hierarchy ({@link Found}, {@link Unset},
 * {@link Invalid}), plus {@link ProviderHealth}, {@link ProviderStatus},
 * and {@link TomlSnapshot}.</p>
 */
public final class ConfigTypes {
    private ConfigTypes() {}

    /**
     * Complete result of reading one option from one provider.
     */
    public sealed interface ProviderResult<T> permits Found, Unset, Invalid {
        T unwrap();
    }

    /**
     * A provider declared a value and coerced it successfully.
     */
    public record Found<T>(T value) implements ProviderResult<T> {
        public Found {
            // value is intentionally not null-checked: the contract allows
            // a legitimate "found null" (e.g. explicit null declaration).
        }
        @Override
        public T unwrap() {
            return value;
        }
    }

    /** A provider made no declaration for an option. */
    public static final class Unset implements ProviderResult<Object> {
        public static final Unset INSTANCE = new Unset();
        private Unset() {}
        @Override public Object unwrap() { throw new IllegalStateException("ProviderResult is Unset"); }
    }

    /**
     * A provider declared a value that its input domain could not coerce.
     */
    public record Invalid(String reason) implements ProviderResult<Object> {
        public Invalid {
            Objects.requireNonNull(reason, "reason");
        }
        @Override
        public Object unwrap() { throw new IllegalStateException("ProviderResult is Invalid: " + reason); }
    }

    /** Health of one configuration source. */
    public enum ProviderHealth {
        OK, MISSING, INDETERMINATE, UNREADABLE, CORRUPT
    }

    /**
     * Health and safe diagnostic detail for one provider.
     *
     * <p>Mirrors the Python
     * {@code deepagents_code.configuration.types.ProviderStatus}.</p>
     *
     * @param name   provider display label
     * @param path   optional path the source was read from
     * @param health health classification
     * @param detail optional diagnostic detail
     */
    public record ProviderStatus(String name, Path path, ProviderHealth health, String detail) {
        public ProviderStatus(String name, Path path, ProviderHealth health) {
            this(name, path, health, null);
        }

        /**
         * Whether the provider can safely participate in resolution.
         *
         * <p>{@code MISSING} is usable because no file at an authoritative
         * path means the administrator deployed no policy.
         * {@code INDETERMINATE} is not: the path itself is a guess, so an
         * empty read proves nothing about what policy the administrator
         * deployed.</p>
         */
        public boolean usable() {
            return health == ProviderHealth.OK || health == ProviderHealth.MISSING;
        }
    }

    /**
     * One parsed TOML source and its health.
     *
     * <p>{@code data} is empty whenever {@code status.health} is not
     * {@link ProviderHealth#OK}, so it must always be read together with
     * {@code status}: an empty table alone cannot distinguish "this
     * source declares nothing" from "this source could not be read".</p>
     *
     * @param data   parsed TOML data, empty when status is unhealthy
     * @param status provider health and display metadata
     * @throws IllegalArgumentException if an unhealthy snapshot carries a
     *     non-empty table
     */
    public record TomlSnapshot(Map<String, Object> data, ProviderStatus status) {
        public TomlSnapshot {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(status, "status");
            if (status.health() != ProviderHealth.OK && !data.isEmpty()) {
                throw new IllegalArgumentException(
                        "a " + status.health() + " snapshot must carry no data; an "
                                + "empty table is what every reader reads as 'nothing declared'");
            }
        }
    }
}
