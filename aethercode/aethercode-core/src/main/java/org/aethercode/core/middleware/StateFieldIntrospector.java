package org.aethercode.core.middleware;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helpers for working with Deep Agents state schemas.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware._state} module. Walks a state schema's
 * fields (and any parent classes) and reports the names of fields marked
 * with {@link PrivateStateAttr}.</p>
 *
 * <p>The Python port uses {@code typing.get_type_hints} to resolve
 * {@code Annotated} metadata lazily, and warns + skips a schema whose
 * annotations cannot be resolved. The Java port does the equivalent via
 * {@link Class#getFields()} &mdash; fields are looked up directly on
 * the class without a separate annotation-resolution step, so there is
 * no "TYPE_CHECKING-only annotation" failure mode to handle. A schema
 * that cannot be inspected (e.g. an interface, abstract class with no
 * instance fields, or a class whose fields all throw on
 * {@code getDeclaredFields()}) is logged at {@code FINE} and contributes
 * no names.</p>
 */
public final class StateFieldIntrospector {
    private static final Logger LOGGER = Logger.getLogger(StateFieldIntrospector.class.getName());

    private StateFieldIntrospector() {}

    /**
     * Return fields annotated with {@link PrivateStateAttr} across the
     * supplied state schemas.
     *
     * <p>The result is the union of all annotated fields across all
     * supplied schemas; a field name that appears in multiple schemas
     * is deduplicated. Static fields and fields from interfaces are
     * skipped. The set is unordered.</p>
     *
     * @param stateSchemas one or more state-schema classes to inspect
     * @return a set of field names that carry the {@link PrivateStateAttr}
     *         marker; never {@code null}
     */
    public static Set<String> privateStateFieldNames(Class<?>... stateSchemas) {
        Set<String> names = new LinkedHashSet<>();
        if (stateSchemas == null) return names;
        for (Class<?> schema : stateSchemas) {
            if (schema == null) continue;
            try {
                collectPrivateFields(schema, names);
            } catch (LinkageError | SecurityException exc) {
                LOGGER.log(Level.WARNING,
                        "Could not introspect state schema " + schema.getName()
                                + "; its PrivateStateAttr fields will NOT be kept private.",
                        exc);
            }
        }
        return names;
    }

    /**
     * Walk a single class's fields, recursing into superclasses, and
     * collect the names of any field carrying {@link PrivateStateAttr}.
     *
     * <p>Record components are inspected via {@link Class#getRecordComponents()};
     * regular class fields via {@link Class#getDeclaredFields()}. The
     * record-component path is needed because the JVM represents record
     * components as private final fields with public accessor methods
     * &mdash; {@code getFields()} does not see them. We deliberately
     * walk both surfaces so a state schema can be either a record or a
     * plain class.</p>
     */
    private static void collectPrivateFields(Class<?> schema, Set<String> names) {
        // 1) Record components (for state schemas declared as records).
        if (schema.isRecord()) {
            for (RecordComponent c : schema.getRecordComponents()) {
                if (hasPrivateMarker(c.getAnnotations())) {
                    names.add(c.getName());
                }
            }
        }
        // 2) Declared fields on this class and all superclasses
        //    (for non-record state schemas, or for additional fields
        //    records may inherit).
        for (Class<?> c = schema; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (Modifier.isStatic(mods)) continue;
                if (hasPrivateMarker(f.getAnnotations())) {
                    names.add(f.getName());
                }
            }
        }
    }

    /**
     * Whether the given array of annotations includes {@link PrivateStateAttr}
     * directly or via a meta-annotation on a composed annotation type.
     */
    public static boolean hasPrivateMarker(Annotation[] annotations) {
        for (Annotation a : annotations) {
            if (a instanceof PrivateStateAttr) return true;
            if (isMetaAnnotatedWith(a.annotationType(), PrivateStateAttr.class)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code field} carries the {@link PrivateStateAttr}
     * annotation directly or via any meta-annotation on a composed
     * annotation type.
     */
    public static boolean hasPrivateMarker(Field field) {
        return hasPrivateMarker(field.getAnnotations());
    }

    /**
     * Whether {@code annotationType} itself is annotated with
     * {@code marker} (transitively). Mirrors the Python port's
     * recursive {@code _has_marker} helper.
     *
     * <p>A {@code visited} set guards against the well-known annotation
     * self-loops in the JDK &mdash; e.g. {@code @Retention} is itself
     * annotated with {@code @Retention} &mdash; which would otherwise
     * trigger unbounded recursion.</p>
     */
    public static boolean isMetaAnnotatedWith(Class<? extends Annotation> annotationType,
                                                Class<? extends Annotation> marker) {
        return isMetaAnnotatedWith(annotationType, marker,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static boolean isMetaAnnotatedWith(Class<? extends Annotation> annotationType,
                                                Class<? extends Annotation> marker,
                                                Set<Class<? extends Annotation>> visited) {
        if (!visited.add(annotationType)) return false;
        for (Annotation a : annotationType.getAnnotations()) {
            Class<? extends Annotation> t = a.annotationType();
            if (t.equals(marker)) return true;
            if (isMetaAnnotatedWith(t, marker, visited)) return true;
        }
        return false;
    }
}
