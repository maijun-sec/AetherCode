package org.aethercode.idea.backend

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import org.aethercode.idea.engine.BackendSettings
import java.nio.file.Path
import java.nio.file.Paths

/**
 * R7: project-scoped holder for the active {@link AetherCodeBackend}.
 *
 * <p>Selection order (per {@link BackendSettings#mode}):
 * <ol>
 *   <li>{@code "daemon"} — force the daemon path, fail loudly if
 *       the supervisor is not reachable.</li>
 *   <li>{@code "in-process"} — force the legacy in-process engine.</li>
 *   <li>{@code "auto"} (default) — try the daemon first, fall back
 *       to in-process if the lock file is missing or the socket
 *       refuses the connection. This mirrors what the TUI does when
 *       it can't find a supervisor.</li>
 * </ol>
 *
 * <p>The selection is cached. If the chosen backend later becomes
 * unavailable (e.g. daemon is killed), the next
 * {@link #current()} call re-runs the auto path. The
 * {@link #reset()} method forces an immediate re-selection.
 */
object BackendManager {

    private val log = logger<BackendManager>()

    @Volatile private var cached: AetherCodeBackend? = null
    @Volatile private var cachedProject: Project? = null

    /** Drop the cached backend and force re-selection on next call. */
    fun reset() {
        synchronized(this) {
            cached?.dispose()
            cached = null
        }
    }

    /**
     * Resolve the backend for {@code project}. Re-uses the cached
     * backend for the same project when its configuration hasn't
     * changed.
     */
    fun current(project: Project): AetherCodeBackend {
        val existing = cached
        if (existing != null && cachedProject === project) return existing
        synchronized(this) {
            val again = cached
            if (again != null && cachedProject === project) return again
            cached?.dispose()
            val settings = BackendSettings.getInstance(project)
            val picked = pick(project, settings)
            cached = picked
            cachedProject = project
            log.info("backend selected: ${picked.id} (mode=${settings.mode}, available=${picked.isAvailable()})")
            return picked
        }
    }

    private fun pick(project: Project, settings: BackendSettings): AetherCodeBackend {
        val inProcess: () -> AetherCodeBackend = { InProcessBackend(project) }
        val daemon: () -> AetherCodeBackend = { DaemonBackend(lockFile(settings), cwd(project), settings.model) }
        return when (settings.mode) {
            BackendSettings.MODE_IN_PROCESS -> inProcess()
            BackendSettings.MODE_DAEMON -> daemon()
            else -> { // auto
                val d = daemon()
                if (d.isAvailable()) d else inProcess()
            }
        }
    }

    private fun lockFile(settings: BackendSettings): Path =
        Paths.get(settings.daemonLockPath.ifBlank { defaultLockPath() })

    private fun cwd(project: Project): Path =
        project.basePath?.let { Paths.get(it) } ?: Paths.get("").toAbsolutePath()

    /** Default lock path mirrors the supervisor's {@code SupervisorHome} on this OS. */
    private fun defaultLockPath(): String {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        return when {
            os.contains("win") -> "$home\\AppData\\Local\\aethercode\\supervisor\\supervisor.port"
            os.contains("mac") -> "$home/Library/Application Support/aethercode/supervisor/supervisor.port"
            else -> "${System.getenv("XDG_RUNTIME_DIR") ?: "$home/.local/state"}/aethercode/supervisor/supervisor.port"
        }
    }
}
