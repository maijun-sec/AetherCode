package org.aethercode.code.hooks;

/**
 * Client approval policy.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.approval_mode.ApprovalMode} {@code StrEnum}.
 * The values are passed to the hook transport under
 * <code>permission_mode</code> when serializing invocations.</p>
 */
public enum ApprovalMode {
    MANUAL,
    AUTO,
    YOLO;

    /** Parse a value case-insensitively, defaulting to {@link #MANUAL}. */
    public static ApprovalMode coerce(Object value) {
        if (value == null) {
            return MANUAL;
        }
        if (value instanceof ApprovalMode am) {
            return am;
        }
        String text = value.toString();
        if (text.isEmpty()) {
            return MANUAL;
        }
        try {
            return ApprovalMode.valueOf(text.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return MANUAL;
        }
    }
}
