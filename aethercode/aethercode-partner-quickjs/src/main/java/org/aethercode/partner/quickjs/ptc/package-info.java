/**
 * Programmatic tool calling (PTC) support for
 * {@code CodeInterpreterMiddleware}. 1:1 port of the Python
 * <code>langchain_quickjs._ptc</code> module. The single
 * {@link org.aethercode.partner.quickjs.ptc.PtcSupport} class exposes
 * the filtering + prompt-rendering surface. The host-function bridge
 * that actually invokes each tool lives in
 * {@code repl.Repl} next to the rest of the context wiring.
 */
package org.aethercode.partner.quickjs.ptc;
