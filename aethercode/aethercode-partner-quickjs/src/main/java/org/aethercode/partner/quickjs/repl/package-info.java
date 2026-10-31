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
 * Thread-keyed QuickJS REPL registry, console bridge, and result
 * formatter. 1:1 port of the Python
 * <code>langchain_quickjs._repl</code> module. The
 * {@link org.aethercode.partner.quickjs.repl.Repl} class is the main
 * entry point; the {@link org.aethercode.partner.quickjs.repl.Registry}
 * holds a {@code thread_id}-keyed map of REPL slots, the
 * {@link org.aethercode.partner.quickjs.repl.EvalOutcome} record is
 * the normalized result of a single eval, and the supporting
 * {@code PtcState}, {@code PtcCallBudgetExceededException},
 * {@code TaskBridgeException}, and {@code ConsoleBuffer} classes
 * model the PTC bridge mechanics.
 */
package org.aethercode.partner.quickjs.repl;
