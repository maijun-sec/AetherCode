package org.aethercode.partner.quickjs.js;

/**
 * Factory helpers for {@link JsExecutor} implementations.
 *
 * <p>Provides the {@link #unsupported()} binding used as the default
 * when no real JavaScript engine is configured.</p>
 */
public final class JsExecutors {

    private JsExecutors() {}

    /**
     * Return an executor that throws {@link UnsupportedOperationException}
     * for every operation. This is the default until a real JS engine
     * binding is registered via
     * {@link System#setProperty(String, String) System.setProperty}
     * (<code>org.aethercode.partner.quickjs.jsExecutor</code>) or a
     * future ServiceLoader mechanism.
     */
    public static JsExecutor unsupported() {
        return UnsupportedJsExecutor.INSTANCE;
    }
}
