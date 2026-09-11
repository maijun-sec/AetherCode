/**
 * Top-level package of the {@code deepagents-code} Java port.
 *
 * <p>This package is the Java 21 native translation of the Python
 * {@code deepagents_code} module. It contains the public API surface used by
 * the {@code dcode} interactive TUI, the agent graph factory shared with the
 * ACP server, model configuration, MCP server discovery and OAuth flows,
 * session persistence, and the various small helpers the rest of the
 * package depends on.</p>
 *
 * <p>Sub-packages (defined in their own {@code package-info.java} files)
 * group the larger systems:</p>
 * <ul>
 *   <li>{@link org.aethercode.code.hooks} — Hooks v2 client/server plumbing.</li>
 *   <li>{@link org.aethercode.code.notifications} — Pending notification helpers.</li>
 *   <li>{@link org.aethercode.code.tools} — Tool registry and display adapters.</li>
 *   <li>{@link org.aethercode.code.ui} — TUI screen/buffer/widget primitives
 *       (Textual-compatible shape; actual rendering is out of scope here).</li>
 * </ul>
 */
package org.aethercode.code;
