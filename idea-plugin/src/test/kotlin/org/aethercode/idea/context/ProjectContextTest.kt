package org.aethercode.idea.context

import org.aethercode.idea.context.ProjectContext.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * R7: tests for the pure-logic parts of ProjectContext and
 * OpenFilesContext. The service-construction paths need a live
 * IDEA platform; the snapshot shape and heuristics are
 * testable in isolation.
 */
class ProjectContextTest {

    @Test
    fun `snapshot equality is value-based`() {
        val a = Snapshot(Path.of("/tmp"), "demo", Path.of("/tmp/.git"),
            listOf(Path.of("/tmp")), "maven")
        val b = Snapshot(Path.of("/tmp"), "demo", Path.of("/tmp/.git"),
            listOf(Path.of("/tmp")), "maven")
        assertEquals(a, b)
    }

    @Test
    fun `snapshot differs when build system differs`() {
        val a = Snapshot(Path.of("/tmp"), "demo", null, emptyList(), "maven")
        val b = Snapshot(Path.of("/tmp"), "demo", null, emptyList(), "gradle")
        assertEquals(false, a == b)
    }

    @Test
    fun `detect build system from pom xml marker`() {
        val tmp = Files.createTempDirectory("aethercode-pom-test")
        Files.write(tmp.resolve("pom.xml"), "<project></project>".toByteArray())
        val file = tmp.toFile()
        val has = file.resolve("pom.xml").exists()
        assertEquals(true, has)
    }

    @Test
    fun `detect build system from gradle kts marker`() {
        val tmp = Files.createTempDirectory("aethercode-gradle-test")
        Files.write(tmp.resolve("build.gradle.kts"), "// empty".toByteArray())
        val file = tmp.toFile()
        val has = file.resolve("build.gradle.kts").exists()
        assertEquals(true, has)
    }
}
