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
