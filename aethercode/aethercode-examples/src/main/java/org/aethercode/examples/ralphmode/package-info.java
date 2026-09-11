/**
 * Java port of {@code deepagents-main/examples/ralph_mode/ralph_mode.py}.
 *
 * <p>Single file:
 * <ul>
 *   <li>{@link org.aethercode.examples.ralphmode.RalphMode} &mdash;
 *       autonomous looping pattern (fresh context per iteration, the
 *       filesystem + git as memory). The Java port mirrors the CLI
 *       surface and the iteration loop, but the inner per-iteration
 *       agent invocation is intentionally a pluggable hook because
 *       the Java port does not depend on {@code deepagents-cli} at
 *       runtime (the deepagents Java CLI does not yet ship an
 *       equivalent of {@code run_non_interactive}).</li>
 * </ul>
 */
package org.aethercode.examples.ralphmode;
