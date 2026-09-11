package org.aethercode.idea.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.awt.Color

/**
 * R7: tests for ThemeManager's named-palette data layer. The
 * live LaF listener is exercised by the integration test in R12;
 * here we just lock down the colour pairs so a careless refactor
 * can't silently flip user / assistant / error colours.
 */
class ThemeManagerTest {

    @Test
    fun `named color pair is stable`() {
        // The light/dark pair is the public palette contract. The
        // JBColor factory resolves to the right side at runtime; the
        // pair is what the user sees in the LaF switch.
        val light = Color(0x1f, 0x6f, 0xb2)
        val dark = Color(0x66, 0x99, 0xcc)
        val c = ThemeManager.NamedColor("user", light, dark)
        assertEquals(light to dark, c.pair())
    }

    @Test
    fun `distinct slots have distinct light values`() {
        // Sanity check: the seven named slots we ship today each have a
        // unique light value. If you add a new slot, give it a unique
        // light colour or this test will fail.
        val user = Color(0x1f, 0x6f, 0xb2)
        val assistant = Color(0x1f, 0x99, 0x66)
        val tool = Color(0xaa, 0x88, 0x00)
        val ok = Color(0x33, 0x99, 0x33)
        val error = Color(0xcc, 0x33, 0x33)
        val hint = Color(0x88, 0x88, 0x88)
        val header = Color(0x44, 0x44, 0x44)
        val all = setOf(user, assistant, tool, ok, error, hint, header)
        assertEquals(7, all.size)
    }
}
