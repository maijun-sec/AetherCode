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

package org.aethercode.deepagents.langchain_compat.messages;

import java.util.List;

/**
 * LangChain-compatible {@code BaseMessage} interface.
 *
 * <p>Java-native port of
 * {@code langchain_core.messages.BaseMessage}. This is the
 * supertype of every LangChain message variant. The Java port
 * reuses {@link LangChainMessage} as the BaseMessage contract
 * (same {@code role()}, {@code content()}, {@code id()}
 * accessors); {@code BaseMessage} is provided as an alias for
 * clarity when working with the compat shim.</p>
 */
public interface BaseMessage extends LangChainMessage {
    /** Stable identifier; reused by the reducer to dedupe writes. */
    @Override
    String id();
}
