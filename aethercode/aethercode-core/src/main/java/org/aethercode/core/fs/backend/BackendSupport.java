package org.aethercode.core.fs.backend;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reflection helpers that mirror the Python deepagents
 * <code>_method_accepts_max_count</code> and
 * <code>execute_accepts_timeout</code> introspection helpers.
 *
 * <p>The Java port keeps these as static methods so concrete backends
 * that don't need them can ignore them; composite backends use the
 * helpers to tolerate older <code>grep</code> / <code>execute</code>
 * signatures.</p>
 */
public final class BackendSupport {
    private BackendSupport() {}

    /**
     * Returns true if {@code backendClass} declares a {@code grep} or
     * {@code agrep} method that accepts the {@code maxCount} keyword.
     */
    public static boolean methodAcceptsMaxCount(Class<?> backendClass, String methodName) {
        try {
            Method m = backendClass.getMethod(methodName, String.class, String.class, String.class, Integer.class);
            if (m == null) return false;
            if (Modifier.isAbstract(m.getModifiers())) {
                // Could be the interface default — look at declared method instead.
                Method declared = findDeclared(backendClass, methodName);
                return declared != null && acceptsMaxCount(declared);
            }
            return acceptsMaxCount(m);
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean acceptsMaxCount(Method m) {
        // The keyword is the third positional param (after pattern, path, glob) or a
        // 4th Integer in the Java port. Walk the parameters; the Java port uses a
        // single Integer parameter named "maxCount" in the API.
        for (java.lang.reflect.Parameter p : m.getParameters()) {
            if (p.getName().equals("maxCount") || p.getName().equals("max_count")) {
                return true;
            }
        }
        return false;
    }

    private static Method findDeclared(Class<?> klass, String name) {
        try {
            return klass.getDeclaredMethod(name, String.class, String.class, String.class, Integer.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Returns true if {@code backendClass.execute(command, timeout)} exists. */
    public static boolean executeAcceptsTimeout(Class<?> backendClass) {
        try {
            backendClass.getMethod("execute", String.class, Integer.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** Returns true if the backend overrides the default {@code delete}. */
    public static boolean supportsDelete(BackendProtocol backend) {
        return backend.getClass().getDeclaredMethods().length > 0
                && !isDefaultDelete(backend);
    }

    private static boolean isDefaultDelete(BackendProtocol backend) {
        try {
            Method m = backend.getClass().getMethod("delete", String.class);
            // Default delete on a direct BackendProtocol implementor is the throw.
            // We approximate by checking if the method's declaring class is Object
            // or contains a default body. For our port the concrete backends always
            // override, so this is rarely false.
            return m.getDeclaringClass() == Object.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** Apply a match cap to a {@link GrepResult}. */
    public static GrepResult applyGrepMaxCount(GrepResult result, Integer maxCount) {
        if (maxCount == null) return result;
        if (result.matches().isEmpty()) return result;
        if (result.matches().get().size() <= maxCount) return result;
        var kept = result.matches().get().subList(0, maxCount);
        return GrepResult.of(java.util.List.copyOf(kept), true);
    }
}
