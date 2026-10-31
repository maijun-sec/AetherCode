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

package org.aethercode.runtime.config;

/**
 * Standard keys for {@link Config#configurable()}.
 *
 * <p>Mirror of langgraph's <code>CONFIG_KEY_*</code> constants.</p>
 */
public final class ConfigKeys {
    private ConfigKeys() {}

    /** Sender queue for command routing inside a graph run. */
    public static final String SEND     = "__pregel_send";

    /** Receiver queue for command routing inside a graph run. */
    public static final String READ     = "__pregel_read";

    /** The {@link org.aethercode.runtime.store.Store} attached to the run. */
    public static final String STORE    = "__pregel_store";

    /** The {@link org.aethercode.runtime.state.StateSnapshot} attached to the run. */
    public static final String STATE    = "__pregel_state";

    /** The {@link org.aethercode.runtime.llm.LLMProvider} attached to the run. */
    public static final String LLM      = "__pregel_llm";

    /** Cached run id used by the ask-permission flow. */
    public static final String RUN_ID   = "__pregel_run_id";

    /** The runtime trace context (parent span, langsmith run, etc). */
    public static final String TRACE    = "__pregel_trace";

    /** Interrupt payload (set by a tool that needs human input). */
    public static final String INTERRUPT = "__pregel_interrupt";
}
