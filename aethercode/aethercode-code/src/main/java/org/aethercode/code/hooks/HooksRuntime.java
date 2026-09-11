package org.aethercode.code.hooks;

/**
 * Convenience typedef for {@link Runtime.HooksRuntime}.
 *
 * <p>Java has no type aliases, so this thin wrapper exists only to
 * give call sites a stable top-level name. Code that needs to refer
 * to the runtime record should import
 * {@link Runtime.HooksRuntime} directly. The {@link #TYPE} constant
 * is the only stable reflective handle for use by tooling that
 * resolves types by name.</p>
 */
public final class HooksRuntime {
    private HooksRuntime() {}

    /** Canonical reflective reference to {@link Runtime.HooksRuntime}. */
    public static final Class<?> TYPE = Runtime.HooksRuntime.class;
}
