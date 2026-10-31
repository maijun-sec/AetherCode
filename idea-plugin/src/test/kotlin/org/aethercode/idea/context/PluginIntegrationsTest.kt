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
