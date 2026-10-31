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
 * Top-level package of the {@code deepagents-code} Java port.
 *
 * <p>This package is the Java 21 native translation of the Python
 * {@code deepagents_code} module. It contains the public API surface used by
 * the {@code dcode} interactive TUI, the agent graph factory shared with the
 * ACP server, model configuration, MCP server discovery and OAuth flows,
 * session persistence, and the various small helpers the rest of the
 * package depends on.</p>
 *
 * <p>Sub-packages (defined in their own {@code package-info.java} files)
 * group the larger systems:</p>
 * <ul>
 *   <li>{@link org.aethercode.code.hooks} — Hooks v2 client/server plumbing.</li>
 *   <li>{@link org.aethercode.code.notifications} — Pending notification helpers.</li>
 *   <li>{@link org.aethercode.code.tools} — Tool registry and display adapters.</li>
 *   <li>{@link org.aethercode.code.ui} — TUI screen/buffer/widget primitives
 *       (Textual-compatible shape; actual rendering is out of scope here).</li>
 * </ul>
 */
package org.aethercode.code;
