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
 * Prompt / rendering helpers for the REPL and PTC system prompts. 1:1
 * port of the Python
 * <code>langchain_quickjs._prompt</code> module. The single
 * {@link org.aethercode.partner.quickjs.prompt.ReplPrompt} class
 * exposes the {@code render_repl_system_prompt},
 * {@code render_subagent_system_prompt},
 * {@code render_eval_tool_code_doc},
 * {@code render_eval_tool_description}, and {@code render_ptc_prompt}
 * functions, plus the supporting case-conversion and JSON-Schema
 * &rarr; TS type renderers.
 */
package org.aethercode.partner.quickjs.prompt;
