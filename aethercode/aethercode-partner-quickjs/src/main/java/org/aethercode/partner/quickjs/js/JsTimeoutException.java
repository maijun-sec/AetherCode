/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.partner.quickjs.js;

/**
 * The QuickJS per-call timeout elapsed. 1:1 port of
 * <code>quickjs_rs.TimeoutError</code>.
 */
public class JsTimeoutException extends JsError {
    public JsTimeoutException(String message) {
        super("Timeout", message, null);
    }
}
