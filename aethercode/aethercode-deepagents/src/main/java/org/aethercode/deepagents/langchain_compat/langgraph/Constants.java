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

package org.aethercode.deepagents.langchain_compat.langgraph;
import org.aethercode.core.runtime.Message.RemoveMessage;

/**
 * LangGraph-compatible constant values used across the
 * port.
 *
 * <p>Java-native port of the constants from
 * {@code langgraph.graph.message},
 * {@code langgraph._internal._constants}, and
 * {@code langgraph.config}.</p>
 */
public final class Constants {
    private Constants() {}

    /**
     * Sentinel id used to clear the entire message list when
     * a {@code RemoveMessage} is written.
     *
     * <p>Java-native port of
     * {@code langgraph.graph.message.REMOVE_ALL_MESSAGES}.</p>
     */
    public static final String REMOVE_ALL_MESSAGES = "__remove_all__";

    /** Config key for reading a value into the current run. */
    public static final String CONFIG_KEY_READ = "configurable.read";

    /** Config key for sending a value out of the current run. */
    public static final String CONFIG_KEY_SEND = "configurable.send";
}
