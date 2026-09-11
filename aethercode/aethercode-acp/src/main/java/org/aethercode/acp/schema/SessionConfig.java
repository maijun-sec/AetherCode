package org.aethercode.acp.schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Session mode and config-option records.
 *
 * <p>Mirrors {@code acp.schema.{SessionMode,SessionModeState,
 * SessionConfigSelectOption,SessionConfigOptionSelect,
 * SessionConfigOptionBoolean}}.</p>
 */
public final class SessionConfig {
    private SessionConfig() {}

    /** A single selectable mode. */
    public record SessionMode(
            String id,
            String name,
            Optional<String> description) {
        public SessionMode {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            description = description == null ? Optional.empty() : description;
        }
    }

    /** State of the session's mode selector. */
    public record SessionModeState(
            List<SessionMode> availableModes,
            String currentModeId) {
        public SessionModeState {
            Objects.requireNonNull(availableModes, "availableModes");
            availableModes = List.copyOf(availableModes);
            Objects.requireNonNull(currentModeId, "currentModeId");
        }
    }

    /** A single option inside a select-type config option. */
    public record SessionConfigSelectOption(
            String value,
            String name,
            Optional<String> description) {
        public SessionConfigSelectOption {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(name, "name");
            description = description == null ? Optional.empty() : description;
        }
    }

    /** Select-type config option (mode / model selector). */
    public record SessionConfigOptionSelect(
            String id,
            String name,
            Optional<String> description,
            String category,
            String type,
            String currentValue,
            List<SessionConfigSelectOption> options)
            implements BareSessionConfigOption {
        public SessionConfigOptionSelect {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(currentValue, "currentValue");
            description = description == null ? Optional.empty() : description;
            category = category == null ? "" : category;
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /** Boolean-type config option. */
    public record SessionConfigOptionBoolean(
            String id,
            String name,
            Optional<String> description,
            String category,
            String type,
            boolean currentValue)
            implements BareSessionConfigOption {
        public SessionConfigOptionBoolean {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            description = description == null ? Optional.empty() : description;
            category = category == null ? "" : category;
        }
    }

    /**
     * Sealed root for the config-option union. Mirrors the
     * optional {@code acp.schema.SessionConfigOption} wrapper
     * the Python port detects via {@code getattr}.
     */
    public sealed interface SessionConfigOption
            permits SessionConfigOptionRoot {
    }

    /**
     * ACP v0.8.x wrapper for a config option. Mirrors
     * {@code acp.schema.SessionConfigOption}.
     */
    public record SessionConfigOptionRoot(SessionConfigOptionSelect root)
            implements SessionConfigOption {
        public SessionConfigOptionRoot {
            Objects.requireNonNull(root, "root");
        }
    }

    /**
     * ACP v0.9+ bare form: a config option is a select or boolean
     * record directly, no wrapper. Mirrors the compatibility
     * alias the Python port uses when {@code SessionConfigOption}
     * is not exposed by the schema.
     */
    public sealed interface BareSessionConfigOption
            permits SessionConfigOptionSelect, SessionConfigOptionBoolean {
    }
}
