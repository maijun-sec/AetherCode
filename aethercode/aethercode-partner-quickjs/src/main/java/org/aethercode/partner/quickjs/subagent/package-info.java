/**
 * QuickJS adapter for the Deep Agents <code>task</code> subagent tool.
 * 1:1 port of the Python
 * <code>langchain_quickjs._subagent</code> module. The single
 * {@link org.aethercode.partner.quickjs.subagent.SubagentBridge} class
 * locates the task tool in the agent's toolset, dispatches subagent
 * calls from inside the JS REPL, and emits lifecycle events on the
 * custom stream. The companion event records
 * ({@link org.aethercode.partner.quickjs.subagent.SubagentStartEvent},
 * {@link org.aethercode.partner.quickjs.subagent.SubagentCompleteEvent},
 * {@link org.aethercode.partner.quickjs.subagent.SubagentErrorEvent})
 * model the wire shape 1:1.
 */
package org.aethercode.partner.quickjs.subagent;
