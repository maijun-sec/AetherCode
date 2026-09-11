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

