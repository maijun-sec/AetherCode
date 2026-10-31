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

package org.aethercode.deepagents.langchain_compat.middleware;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for fields that should be hidden from the
 * public state schema in LangChain-compatible middleware.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.types.PrivateStateAttr}. The
 * annotation marks fields on a state schema that are part of
 * internal bookkeeping but should not appear in the agent's
 * I/O schema &mdash; useful for accumulating evaluator history,
 * iteration counters, or other values the runtime tracks on
 * behalf of the middleware but does not surface to the model.</p>
 *
 * <p>The {@code org.aethercode.core.middleware.PrivateStateAttr} class
 * in our project has the same semantics; this class is the
 * LangChain-compatible alias so middleware ported from
 * LangChain Python can use either name.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE, ElementType.TYPE})
public @interface PrivateStateAttr {
}

