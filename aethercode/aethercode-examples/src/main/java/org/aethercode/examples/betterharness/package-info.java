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
