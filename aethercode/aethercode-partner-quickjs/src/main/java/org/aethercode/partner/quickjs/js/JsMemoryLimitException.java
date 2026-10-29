/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.partner.quickjs.js;

/**
 * The runtime's heap limit was exceeded. 1:1 port of
 * <code>quickjs_rs.MemoryLimitError</code>.
 */
public class JsMemoryLimitException extends JsError {
    public JsMemoryLimitException(String message) {
        super("OutOfMemory", message, null);
    }
}
