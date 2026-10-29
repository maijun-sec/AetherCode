/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.partner.quickjs.js;

/**
 * Overlapping {@code eval} on the same context. 1:1 port of
 * <code>quickjs_rs.ConcurrentEvalError</code>.
 */
public class JsConcurrentEvalException extends JsError {
    public JsConcurrentEvalException(String message) {
        super("ConcurrentEval", message, null);
    }
}
