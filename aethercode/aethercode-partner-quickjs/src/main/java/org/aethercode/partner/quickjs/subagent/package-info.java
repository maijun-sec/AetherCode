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
 * QuickJS adapter for the Deep Agents <code>task</code> subagent tool.
 * 1:1 port of the Python
 * <code>langchain_quickjs._subagent</code> module. The single
 * {@link org.aethercode.partner.quickjs.subagent.SubagentBridge} class
 * locates the task tool in the agent's toolset, dispatches subagent
 * calls from inside the JS REPL, and emits lifecycle events on the
 * custom stream. The companion event records
 * ({@link org.aethercode.partner.quickjs.subagent.SubagentStartEvent},
 * {@link org.aethercode.partner.quickjs.subagent.SubagentCompleteEvent},
 * {@link org.aethercode.partner.quickjs.subagent.SubagentErrorEvent})
 * model the wire shape 1:1.
 */
package org.aethercode.partner.quickjs.subagent;
