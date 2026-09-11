package org.aethercode.examples.betterharness;

/**
 * Test plugin entrypoint for better-harness.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/better-harness/better_harness_plugin.py}.
 * Applies the saved variant from {@link BetterHarnessPatching#VARIANT_ENV}
 * at class load time &mdash; mirroring the Python port's
 * {@code conftest.py} plugin that calls
 * {@code patch_from_env()} on import.</p>
 *
 * <p>Callers wire this up through a JUnit extension, a {@code main}
 * method, or a static initializer; the port does not assume a
 * particular integration point.</p>
 */
public final class BetterHarnessPlugin {
    private BetterHarnessPlugin() {}

    /** Apply the saved variant from the environment. */
    public static void applyFromEnv() {
        BetterHarnessPatching.patchFromEnv();
    }

    /** Convenience main entry point. */
    public static void main(String[] args) {
        applyFromEnv();
    }
}
