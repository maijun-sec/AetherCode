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
