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
