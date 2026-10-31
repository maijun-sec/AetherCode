/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
