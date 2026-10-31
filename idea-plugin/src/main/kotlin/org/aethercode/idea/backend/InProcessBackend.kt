// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

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
