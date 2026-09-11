package org.aethercode.talon.channels;

/**
 * Who may trigger a channel-backed agent.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_talon.channels.base.ExposureMode} {@code StrEnum}.</p>
 */
public enum ExposureMode {
    SELF,
    ALLOWLIST,
    OPEN;

    public String value() {
        return name().toLowerCase();
    }

    public static ExposureMode fromValue(String value) {
        if (value == null) {
            return SELF;
        }
        return ExposureMode.valueOf(value.toUpperCase());
    }
}
