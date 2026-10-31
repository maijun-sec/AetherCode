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

package org.aethercode.core.runtime.llm;

import java.util.Map;

/**
 * Strategy for materializing a model spec into a chat-model object.
 *
 * <p>Java-native port of the contract used by
 * {@code deepagents._models.resolve_model}: provider- or
 * runtime-specific code can register a factory via
 * {@link ChatModelFactoryRegistry#register(String, ChatModelFactory)}
 * and the resolver will route the spec through it.</p>
 *
 * <p>Implementations are expected to be idempotent and
 * thread-safe: the resolver may call {@link #create} on any
 * worker thread.</p>
 */
@FunctionalInterface
public interface ChatModelFactory {
    /**
     * Build a chat model for {@code modelSpec}, applying the
     * already-merged provider profile as {@code kwargs}.
     */
    Object create(String modelSpec, Map<String, Object> kwargs);
}
