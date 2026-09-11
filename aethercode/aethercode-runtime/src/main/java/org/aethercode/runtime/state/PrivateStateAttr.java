package org.aethercode.runtime.state;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a state field as private — read &amp; write by middleware only,
 * never serialized to the LLM prompt.
 *
 * <p>Mirror of langchain's <code>PrivateStateAttr</code>.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface PrivateStateAttr {
}
