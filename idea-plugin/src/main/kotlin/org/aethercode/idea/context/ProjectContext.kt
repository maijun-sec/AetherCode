package org.aethercode.idea.context

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import java.nio.file.Paths

/**
 * R7: project context. Captures the state of the currently
 * opened project in a snapshot the rest of the plugin can read
 * synchronously, and broadcasts change events on
 * {@link ContextBus#PROJECT_CHANGED}.
 *
 * <p>Snapshot contents:
 * <ul>
 *   <li>{@code basePath} — project root on disk</li>
 *   <li>{@code name} — display name</li>
 *   <li>{@code vcsRoot} — first VCS root, if any (git/svn/hg)</li>
 *   <li>{@code contentRoots} — content roots from the project model</li>
 *   <li>{@code buildSystem} — heuristic: "maven" / "gradle" / "unknown"</li>
 * </ul>
 *
 * <p>Heuristics are intentionally cheap — R7 only checks for
 * the presence of a small set of marker files. R12+ can swap in
 * a proper Maven/Gradle model query.
 */
@Service(Service.Level.PROJECT)
class ProjectContext(private val project: Project) {

    private val log = logger<ProjectContext>()

    @Volatile private var snapshot: Snapshot = compute()

    init {
        // Re-compute on the project's message bus: ProjectRootListener
        // covers module/content-root changes, and ProjectLevelVcsManager
        // gives us a VCS-root listener. We coalesce into one snapshot.
        val conn = project.messageBus.connect()
        conn.subscribe(ProjectRootManager.PROJECT_ROOTS, object : com.intellij.openapi.roots.ProjectRootManagerListener {
            override fun rootsChanged(event: com.intellij.openapi.roots.ProjectRootManagerEvent?) { refresh() }
        })
    }

    fun current(): Snapshot = snapshot

    fun refresh() {
        val fresh = compute()
        val prev = snapshot
        snapshot = fresh
        if (fresh != prev) {
            log.info("project snapshot changed: ${prev.name} → ${fresh.name}")
            project.messageBus.syncPublisher(ContextBus.PROJECT_CHANGED).projectChanged(fresh)
        }
    }

    private fun compute(): Snapshot {
        val base = project.basePath?.let { Paths.get(it) }
        val vcs = try {
            ProjectLevelVcsManager.getInstance(project).allVcsRoots.firstOrNull()?.let { Paths.get(it.path) }
        } catch (_: Exception) { null }
        val roots: List<Path> = try {
            ProjectRootManager.getInstance(project).contentRoots.mapNotNull { it.toNioPathOrNull() }
        } catch (_: Exception) { emptyList() }
        val build = detectBuildSystem(base)
        return Snapshot(
            basePath = base,
            name = project.name,
            vcsRoot = vcs,
            contentRoots = roots,
            buildSystem = build
        )
    }

    private fun detectBuildSystem(base: Path?): String {
        if (base == null) return "unknown"
        val pom = base.resolve("pom.xml")
        val gradle = base.resolve("build.gradle")
        val gradleKts = base.resolve("build.gradle.kts")
        return when {
            pom.exists() -> "maven"
            gradleKts.exists() || gradle.exists() -> "gradle"
            else -> "unknown"
        }
    }

    private fun VirtualFile.toNioPathOrNull(): Path? = try { Paths.get(path) } catch (_: Exception) { null }

    /** Immutable snapshot. Equality is value-based, so {@link #refresh} can dedupe. */
    data class Snapshot(
        val basePath: Path?,
        val name: String,
        val vcsRoot: Path?,
        val contentRoots: List<Path>,
        val buildSystem: String
    )

    companion object {
        fun getInstance(project: Project): ProjectContext = project.getService(ProjectContext::class.java)
    }
}
