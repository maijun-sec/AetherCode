package org.aethercode.idea.backend

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger

/**
 * R7: drop the cached {@link AetherCodeBackend} so the next
 * {@link BackendManager#current} call re-runs selection. This
 * is the user-facing escape hatch for "I started the daemon
 * after AetherCode picked in-process mode" and similar
 * sequencing problems.
 *
 * <p>Bound to {@code Alt+Shift+B} via {@code plugin.xml}. The
 * action is intentionally global (not project-scoped) — the
 * backend is per-project but the re-select is cheap, and a
 * per-project shortcut is harder to discover.
 */
class ResetBackendAction : AnAction() {
    private val log = logger<ResetBackendAction>()
    override fun actionPerformed(e: AnActionEvent) {
        BackendManager.reset()
        log.info("backend cache reset by user")
    }
}
