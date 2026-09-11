package org.aethercode.code.hooks.models;

/**
 * Normalized permission result from hook processing.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.domain.PermissionEffect} record.</p>
 */
public record PermissionEffect(String behavior, String reason, boolean interrupt) {
    public static final String ALLOW = "allow";
    public static final String DENY = "deny";
    public static final String ASK = "ask";
    public static final String NONE = "none";

    public static PermissionEffect allow() {
        return new PermissionEffect(ALLOW, null, false);
    }

    public static PermissionEffect deny(String reason) {
        return new PermissionEffect(DENY, reason, false);
    }

    public static PermissionEffect ask(String reason) {
        return new PermissionEffect(ASK, reason, false);
    }

    public static PermissionEffect none() {
        return new PermissionEffect(NONE, null, false);
    }
}
