package org.aethercode.partner.quickjs.js;

/**
 * The QuickJS <code>undefined</code> sentinel. Marshaling returns
 * fresh instances of this type rather than {@code null}, so identity
 * checks are unreliable; use {@link #isUndefined(Object)} instead.
 */
public final class JsUndefined {

    public static final JsUndefined INSTANCE = new JsUndefined();

    private JsUndefined() {}

    @Override
    public String toString() {
        return "undefined";
    }

    /** Type-safe {@code instanceof} check. */
    public static boolean isUndefined(Object value) {
        return value instanceof JsUndefined;
    }
}
