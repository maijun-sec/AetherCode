/**
 * Formatting and output-coercion helpers for the QuickJS REPL.
 * 1:1 port of the Python
 * <code>langchain_quickjs._format</code> module. The single
 * {@link org.aethercode.partner.quickjs.format.Format} class bundles
 * the JS-value &rarr; Java string coercion, the PTC bridge marshaling
 * shape, and the {@link org.aethercode.partner.quickjs.repl.EvalOutcome}
 * &rarr; tool-output wire format.
 */
package org.aethercode.partner.quickjs.format;
