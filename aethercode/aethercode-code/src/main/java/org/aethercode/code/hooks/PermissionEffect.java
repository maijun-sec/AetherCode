package org.aethercode.code.hooks;

/**
 * Normalized permission effect produced by a hook.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.PermissionEffect} record.
 * A <code>behavior</code> of {@link Behavior#NONE} means the hook had no
 * opinion.</p>
 *
 * @param behavior  one of {@code allow}, {@code deny}, {@code ask}, {@code none}
 * @param reason    human-readable reason for the effect, may be {@code null}
 * @param interrupt whether the call should escalate to HITL review when denied
 */
public record PermissionEffect(Behavior behavior, String reason, boolean interrupt) {

    /** Permission behavior values, mirroring the Python literal type. */
    public enum Behavior {
        ALLOW,
        DENY,
        ASK,
        NONE
    }

    public PermissionEffect {
        if (behavior == null) {
            behavior = Behavior.NONE;
        }
    }

    /** Convenience: build a behavior with no reason and no interrupt flag. */
    public static PermissionEffect of(Behavior behavior) {
        return new PermissionEffect(behavior, null, false);
    }
}
