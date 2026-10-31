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

package org.aethercode.core.middleware;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation that designates a state-schema field as "private" to the
 * middleware that owns it.
 *
 * <p>Java-native port of LangChain's
 * {@code langchain.agents.middleware.types.PrivateStateAttr}. A field
 * annotated with {@code @PrivateStateAttr} on a state-schema class is
 * filtered out when the state is shared across middleware or subagents &mdash;
 * the field is kept on the owner middleware's local state and never merged
 * into the public agent state.</p>
 *
 * <p>The Java port uses a {@link RetentionPolicy#RUNTIME} retention so the
 * marker is queryable at runtime via reflection, and
 * {@link ElementType#FIELD} (records) so the field-by-field introspection
 * in {@link StateFieldIntrospector} can find it.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({
        ElementType.FIELD,
        ElementType.RECORD_COMPONENT,
        ElementType.ANNOTATION_TYPE,
        ElementType.TYPE
})
public @interface PrivateStateAttr {
}
