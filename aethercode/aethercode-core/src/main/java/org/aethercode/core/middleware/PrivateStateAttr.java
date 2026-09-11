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
