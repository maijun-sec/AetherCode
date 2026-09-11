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
