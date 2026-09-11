package org.aethercode.partner.quickjs.js;

/**
 * A Promise could not be resolved and no async host work was in
 * flight. 1:1 port of <code>quickjs_rs.DeadlockError</code>.
 */
public class JsDeadlockException extends JsError {
    public JsDeadlockException(String message) {
        super("Deadlock", message, null);
    }
}
