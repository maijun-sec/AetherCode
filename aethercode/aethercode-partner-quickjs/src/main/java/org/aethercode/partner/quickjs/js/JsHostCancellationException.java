package org.aethercode.partner.quickjs.js;

/**
 * A host function (or pending operation) was cancelled. 1:1 port of
 * <code>quickjs_rs.HostCancellationError</code>.
 */
public class JsHostCancellationException extends JsError {
    public JsHostCancellationException(String message) {
        super("HostCancellation", message, null);
    }
}
