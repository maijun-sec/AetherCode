package org.aethercode.idea.backend

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import org.aethercode.core.stream.StreamEvent
import org.aethercode.idea.engine.AetherCodeEngineHolder

/**
 * R7: backend that runs the agent in-process, using the same
 * {@code AetherCodeEngine} the legacy chat panel called directly.
 *
 * <p>Pros: zero infra, no daemon to spawn, no socket. Cons: only
 * one session per IDE window, can't share state with tui/desktop.
 *
 * <p>This is the default fallback when {@link DaemonBackend} cannot
 * reach a running supervisor. It is also the choice for users who
 * explicitly want in-process mode (e.g. corporate lockdown that
 * blocks the supervisor socket).
 */
class InProcessBackend(private val project: Project) : AetherCodeBackend {

    private val log = logger<InProcessBackend>()

    @Volatile private var running = false

    override val id: String = "in-process"
    override val label: String = "In-Process (legacy)"

    override fun isAvailable(): Boolean = true

    override fun query(prompt: String): Sequence<StreamEvent> {
        running = true
        return try {
            val engine = AetherCodeEngineHolder.getEngine(project)
            engine.query(prompt).asSequence()
        } catch (e: Exception) {
            log.warn("in-process query failed: ${e.message}", e)
            emptySequence()
        } finally {
            running = false
        }
    }

    override fun abort() {
        // R7 keeps the legacy in-process path: abort is wired through
        // the chat panel's stop button calling engine.query cancellation
        // indirectly. The legacy path does not yet expose a clean cancel
        // hook; the chat panel's CompletableFuture is the de-facto abort.
        // (A real cancellation token is on the R7.5 plan — once we
        // also wire DaemonBackend the two paths share a CommonAbort.)
        running = false
    }

    override fun dispose() { /* nothing to release */ }
}
