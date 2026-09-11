package org.aethercode.partner.quickjs.js;

/**
 * Source transform flags applied to every {@code eval} under a
 * {@link JsRuntime}. 1:1 port of
 * <code>quickjs_rs.SourceTransform</code>.
 */
public enum JsSourceTransform {
    /**
     * Rewrite top-level <code>const</code> to <code>var</code> so the
     * REPL can re-declare globals across calls without an
     * "already declared" error.
     */
    TOP_LEVEL_CONST_TO_VAR
}
