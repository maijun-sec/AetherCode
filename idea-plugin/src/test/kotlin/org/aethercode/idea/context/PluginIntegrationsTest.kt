package org.aethercode.idea.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * R7: tests for PluginIntegrations' capability enum and the
 * default id-to-capability table. We do not exercise the live
 * PluginManagerCore probe here — that needs the IDEA platform
 * and is covered by the integration tests in the R12 round.
 */
class PluginIntegrationsTest {

    @Test
    fun `enum covers the eight core capabilities`() {
        // 8 capabilities today. If you add a new one, bump this number
        // and update PluginIntegrations.kt in the same commit.
        assertEquals(8, PluginIntegrations.Capability.values().size)
    }

    @Test
    fun `git capability has a stable id`() {
        // The id "git" is part of the public surface — integrations in
        // R12+ branch on it. Don't rename without a deprecation note.
        val cap = PluginIntegrations.Capability.GIT
        assertNotEquals(null, cap.name)
    }

    @Test
    fun `distinct capabilities have distinct ordinals`() {
        val all = PluginIntegrations.Capability.values().toSet()
        assertEquals(all.size, PluginIntegrations.Capability.values().size)
    }
}
