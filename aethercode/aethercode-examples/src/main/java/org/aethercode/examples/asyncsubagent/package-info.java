/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
/**
 * Java port of {@code deepagents-main/examples/async-subagent-server/}.
 *
 * <p>Two files:
 * <ul>
 *   <li>{@link org.aethercode.examples.asyncsubagent.AsyncSubagentServer}
 *       &mdash; a {@code com.sun.net.httpserver} server that exposes a
 *       research subagent over the Agent Protocol surface, mirroring
 *       the FastAPI {@code server.py}.</li>
 *   <li>{@link org.aethercode.examples.asyncsubagent.AsyncSubagentSupervisor}
 *       &mdash; a REPL supervisor that delegates research to the
 *       server-hosted subagent using the
 *       {@link org.aethercode.middleware.AsyncSubAgent} config, mirroring
 *       the {@code supervisor.py}.</li>
 * </ul>
 */
package org.aethercode.examples.asyncsubagent;
