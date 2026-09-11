/**
 * Thread-keyed QuickJS REPL registry, console bridge, and result
 * formatter. 1:1 port of the Python
 * <code>langchain_quickjs._repl</code> module. The
 * {@link org.aethercode.partner.quickjs.repl.Repl} class is the main
 * entry point; the {@link org.aethercode.partner.quickjs.repl.Registry}
 * holds a {@code thread_id}-keyed map of REPL slots, the
 * {@link org.aethercode.partner.quickjs.repl.EvalOutcome} record is
 * the normalized result of a single eval, and the supporting
 * {@code PtcState}, {@code PtcCallBudgetExceededException},
 * {@code TaskBridgeException}, and {@code ConsoleBuffer} classes
 * model the PTC bridge mechanics.
 */
package org.aethercode.partner.quickjs.repl;
