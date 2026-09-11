package org.aethercode.partner.quickjs.js;

/**
 * The handle's value could not be marshalled to a Java type
 * (functions, symbols, circular structures). 1:1 port of
 * <code>quickjs_rs.MarshalError</code>.
 */
public class JsMarshalException extends JsError {
    public JsMarshalException(String message) {
        super("MarshalError", message, null);
    }

    public JsMarshalException(String message, Throwable cause) {
        super("MarshalError", message, null);
        initCause(cause);
    }
}
