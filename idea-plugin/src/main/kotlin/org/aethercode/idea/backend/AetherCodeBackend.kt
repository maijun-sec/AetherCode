package org.aethercode.idea.backend

import org.aethercode.core.stream.StreamEvent

/**
 * R7: AetherCode backend abstraction. The chat panel talks to this
 * interface and never directly to the underlying engine. Two
 * implementations:
 *
 * <ul>
 *   <li>{@link InProcessBackend} — wraps the legacy
 *       {@code AetherCodeEngineHolder} path. The agent runs in the
 *       IDE's JVM. This is the fallback when no daemon is reachable.</li>
 *   <li>{@link DaemonBackend} — talks to a running aethercode-tasks
 *       supervisor over JSON-RPC 2.0 / TCP loopback, exactly the way
 *       the TUI and Desktop connect. Multi-session, multi-agent
 *       lives in the supervisor. The IDE plugin becomes a "remote
 *       view" of the same surface.</li>
 * </ul>
 *
 * <p>The mode is selected by {@link BackendManager}, which honours
 * {@link BackendSettings#mode}.
 */
interface AetherCodeBackend {

    /** Stable id of the implementation — e.g. {@code "in-process"}, {@code "daemon"}. */
    val id: String

    /** Human label for the settings dropdown. */
    val label: String

    /** True when the backend is ready to accept queries. */
    fun isAvailable(): Boolean

    /**
     * Run one query, returning a stream of events identical in shape to
     * {@code AetherCodeEngine.query()}. Backends are expected to be
     * non-blocking: callers should consume the stream on a background
     * thread and forward events to the UI via {@code invokeLater}.
     */
    fun query(prompt: String): Sequence<StreamEvent>

    /**
     * Best-effort abort of the most recent query on this backend.
     * Implementations may no-op if no query is running.
     */
    fun abort()

    /** Free resources (sockets, file handles, daemon child refs). */
    fun dispose()
}
