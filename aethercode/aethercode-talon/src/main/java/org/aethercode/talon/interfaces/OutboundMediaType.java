/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.talon.interfaces;

/**
 * Channel-level media category used by {@link ChannelMedia}.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_talon.interfaces.OutboundMediaType} literal type.</p>
 */
public enum OutboundMediaType {
    IMAGE,
    VIDEO,
    DOCUMENT,
    AUDIO,
    VOICE;

    /** Returns the lower-case form used in Python literals. */
    public String value() {
        return name().toLowerCase();
    }

    /** Parse the lower-case form (image / video / document / audio / voice). */
    public static OutboundMediaType fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("media type must not be null");
        }
        return OutboundMediaType.valueOf(value.toUpperCase());
    }
}
