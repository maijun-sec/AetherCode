/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.talon;

/**
 * Raised when Talon runtime configuration is invalid.
 *
 * <p>Java-native port of {@code deepagents_talon.config.TalonConfigError}.</p>
 */
public class TalonConfigError extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public TalonConfigError(String message) {
        super(message);
    }
}
