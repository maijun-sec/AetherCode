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

/**
 * Java port of {@code deepagents-main/examples/better-harness/}.
 *
 * <p>Five files:
 * <ul>
 *   <li>{@link org.aethercode.examples.betterharness.BetterHarnessPlugin}
 *       &mdash; entry point used to apply module-attribute patches from
 *       a saved variant file.</li>
 *   <li>{@link org.aethercode.examples.betterharness.BetterHarnessPatching}
 *       &mdash; surface patching helpers (variant construction,
 *       workspace override, PYTHONPATH, sitecustomize).</li>
 *   <li>{@link org.aethercode.examples.betterharness.BetterHarnessCore}
 *       &mdash; core data model ({@code Experiment}, {@code Variant},
 *       {@code SplitResult}, {@code RunReport}, ...), config loader,
 *       run loop, trace-ref helpers, and the CLI.</li>
 *   <li>{@link org.aethercode.examples.betterharness.BetterHarnessRunners}
 *       &mdash; pytest and Harbor eval runners with JUnit + junit-xml
 *       parsing.</li>
 *   <li>{@link org.aethercode.examples.betterharness.BetterHarnessAgent}
 *       &mdash; outer-loop Deep Agent (proposer workspace) and
 *       subprocess invoker that runs the proposer against a pulled
 *       workspace.</li>
 * </ul>
 *
 * <p>The Java port is an illustrative translation: the run loop and
 * CLI behavior mirror the Python port one-for-one, but no subprocess
 * is launched at port-time (no {@code uv} or Python interpreter is
 * assumed). Callers wire up their own runtime as needed.</p>
 */
package org.aethercode.examples.betterharness;
