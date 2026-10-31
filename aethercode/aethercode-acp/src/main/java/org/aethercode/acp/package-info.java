/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/**
 * Java port of the Python {@code deepagents-acp} module.
 *
 * <p>Provides an Agent Client Protocol (ACP) server that bridges
 * a Deep Agent (or any {@link org.aethercode.langchain_compat.langgraph.CompiledStateGraph}
 * wrapped as a {@link org.aethercode.acp.StreamingStateGraph})
 * with an ACP client (IDE / frontend). The API mirrors the
 * Python port 1:1; the only divergence is the lack of a
 * Java ACP transport implementation in this module &mdash;
 * real transports (stdio, websocket, IDE frontends, ...)
 * live in the host application.</p>
 *
 * <h2>Public entry points</h2>
 * <ul>
 *   <li>{@link org.aethercode.acp.AcpVersion} &mdash; version constant.</li>
 *   <li>{@link org.aethercode.acp.AgentServerACP} &mdash; the main agent
 *       server class. Subclass or instantiate to provide a
 *       custom agent.</li>
 *   <li>{@link org.aethercode.acp.AgentSessionContext} &mdash;
 *       per-session context passed to an agent factory.</li>
 *   <li>{@link org.aethercode.acp.StreamingStateGraph} &mdash; the
 *       interface a graph runtime implements to feed the
 *       server's prompt loop.</li>
 *   <li>{@link org.aethercode.acp.Utils} &mdash; content-block
 *       conversion and shell-injection heuristics.</li>
 *   <li>{@link org.aethercode.acp.Main} &mdash; the {@code main}
 *       entry point (default test agent).</li>
 * </ul>
 *
 * <h2>Schema</h2>
 * The {@link org.aethercode.acp.schema} subpackage contains the
 * ACP wire-format records (content blocks, MCP servers, config
 * options, plan entries, session updates, response types).
 *
 * <h2>Examples</h2>
 * The {@link org.aethercode.acp.examples} subpackage contains
 * reference middlewares and demo agents. They are marked
 * {@code @Example} in their Javadoc to flag them as illustrative
 * rather than core hot-path code.
 */
package org.aethercode.acp;
