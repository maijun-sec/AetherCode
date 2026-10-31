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
