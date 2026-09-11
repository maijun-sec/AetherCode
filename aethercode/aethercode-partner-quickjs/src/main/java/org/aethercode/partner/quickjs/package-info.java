/**
 * Java 21 native port of the <code>langchain_quickjs</code> partner
 * package &mdash; a sandboxed JavaScript REPL exposed to a deepagents
 * agent as the <code>eval</code> tool.
 *
 * <p>Mirrors the seven Python source files 1:1:</p>
 * <ul>
 *   <li>{@link org.aethercode.partner.quickjs.CodeInterpreterMiddleware} &mdash;
 *       <code>middleware.py</code> (the public entry point)</li>
 *   <li>{@link org.aethercode.partner.quickjs.format.Format} &mdash;
 *       <code>_format.py</code></li>
 *   <li>{@link org.aethercode.partner.quickjs.prompt.ReplPrompt} &mdash;
 *       <code>_prompt.py</code></li>
 *   <li>{@link org.aethercode.partner.quickjs.ptc.PtcSupport} &mdash;
 *       <code>_ptc.py</code></li>
 *   <li>{@link org.aethercode.partner.quickjs.repl.Repl} &mdash;
 *       <code>_repl.py</code></li>
 *   <li>{@link org.aethercode.partner.quickjs.snapshot.SnapshotCodec} &mdash;
 *       <code>_snapshot.py</code></li>
 *   <li>{@link org.aethercode.partner.quickjs.subagent.SubagentBridge} &mdash;
 *       <code>_subagent.py</code></li>
 * </ul>
 *
 * <p>Actual JavaScript execution is delegated to a pluggable
 * {@link org.aethercode.partner.quickjs.js.JsExecutor}. The default
 * {@link org.aethercode.partner.quickjs.js.JsExecutors#unsupported()
 * unsupported} binding throws {@link UnsupportedOperationException} on
 * every operation; the port can be wired up later with a GraalVM JS or
 * <code>javax.script</code> backend without touching the public API.</p>
 */
package org.aethercode.partner.quickjs;
