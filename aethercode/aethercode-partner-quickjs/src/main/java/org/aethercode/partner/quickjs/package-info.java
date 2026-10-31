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
 * Java 21 native port of the <code>langchain_quickjs</code> partner
 * package &mdash; a sandboxed JavaScript REPL exposed to a deepagents
 * agent as the <code>eval</code> tool.
 *
 * <p>Mirrors the seven Python source files 1:1:</p>
 * <ul>
 *   <li>{@link org.aethercode.partner.quickjs.CodeInterpreterMiddleware} &mdash;
 *       <code>middleware.py</code> (the public entry point)</li>
 *   <li>{@link org.aethercode.partner.quickjs.format.Format} &mdash;
 *       <code>_format.py</code></li>
 *   <li>{@link org.aethercode.partner.quickjs.prompt.ReplPrompt} &mdash;
 *       <code>_prompt.py</code></li>
 *   <li>{@link org.aethercode.partner.quickjs.ptc.PtcSupport} &mdash;
 *       <code>_ptc.py</code></li>
 *   <li>{@link org.aethercode.partner.quickjs.repl.Repl} &mdash;
 *       <code>_repl.py</code></li>
 *   <li>{@link org.aethercode.partner.quickjs.snapshot.SnapshotCodec} &mdash;
 *       <code>_snapshot.py</code></li>
 *   <li>{@link org.aethercode.partner.quickjs.subagent.SubagentBridge} &mdash;
 *       <code>_subagent.py</code></li>
 * </ul>
 *
 * <p>Actual JavaScript execution is delegated to a pluggable
 * {@link org.aethercode.partner.quickjs.js.JsExecutor}. The default
 * {@link org.aethercode.partner.quickjs.js.JsExecutors#unsupported()
 * unsupported} binding throws {@link UnsupportedOperationException} on
 * every operation; the port can be wired up later with a GraalVM JS or
 * <code>javax.script</code> backend without touching the public API.</p>
 */
package org.aethercode.partner.quickjs;
