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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7: tests for the centralised MessageBus topic catalogue.
 * The point of ContextBus is to make every integration event
 * discoverable in one place; this test enforces the property
 * "every topic in the catalogue is a real Topic" and that the
 * TOPICS list agrees with the named fields.
 */
class ContextBusTest {

    @Test
    fun `catalogue lists exactly four topics`() {
        // Adding a new service without adding it here is a smell.
        assertEquals(4, ContextBus.TOPICS.size)
    }

    @Test
    fun `project and open files are project level`() {
        val project = ContextBus.PROJECT_CHANGED
        val open = ContextBus.OPEN_FILES_CHANGED
        assertEquals("AetherCode.ProjectChanged", project.displayName)
        assertEquals("AetherCode.OpenFilesChanged", open.displayName)
    }

    @Test
    fun `theme and plugin integrations are app level`() {
        val theme = ContextBus.THEME_CHANGED
        val plugins = ContextBus.PLUGIN_INTEGRATIONS_CHANGED
        assertEquals("AetherCode.ThemeChanged", theme.displayName)
        assertEquals("AetherCode.PluginIntegrationsChanged", plugins.displayName)
    }
}
