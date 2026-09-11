/**
 * JavaScript engine binding for the QuickJS REPL. 1:1 port of the
 * <code>quickjs_rs</code> surface the Python <code>_repl.py</code>
 * uses. A concrete {@link org.aethercode.partner.quickjs.js.JsExecutor}
 * implementation wraps a real engine (GraalVM JS, <code>javax.script</code>,
 * JNI to the Rust <code>quickjs</code> crate, ...). The default
 * {@link org.aethercode.partner.quickjs.js.JsExecutors#unsupported()
 * unsupported} binding throws {@link UnsupportedOperationException} on
 * every operation.
 */
package org.aethercode.partner.quickjs.js;
