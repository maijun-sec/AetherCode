/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.memory;

import org.aethercode.memory.ProceduralMemory.Procedure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProceduralMemoryTest {

    @Test
    void addAndGet() {
        ProceduralMemory m = new ProceduralMemory();
        Procedure p = m.add("login", "How to log in", List.of("step 1", "step 2"));
        assertNotNull(p.id());
        assertEquals("login", p.name());
        assertEquals(p.id(), m.get(p.id()).id());
    }

    @Test
    void autoIdWhenBlank() {
        Procedure p = new Procedure(null, "x", "d", null, null, 0);
        assertNotNull(p.id());
    }

    @Test
    void remove() {
        ProceduralMemory m = new ProceduralMemory();
        Procedure p = m.add("a", "a", List.of());
        assertTrue(m.remove(p.id()));
        assertFalse(m.remove(p.id()));
    }

    @Test
    void size() {
        ProceduralMemory m = new ProceduralMemory();
        assertEquals(0, m.size());
        m.add("a", "a", List.of());
        m.add("b", "b", List.of());
        assertEquals(2, m.size());
    }

    @Test
    void all() {
        ProceduralMemory m = new ProceduralMemory();
        m.add("a", "a", List.of());
        m.add("b", "b", List.of());
        assertEquals(2, m.all().size());
    }

    @Test
    void findByKeyword() {
        ProceduralMemory m = new ProceduralMemory();
        m.add("login", "Log into the API", List.of());
        m.add("logout", "Log out", List.of());
        m.add("reset", "Reset password", List.of());
        List<Procedure> matches = m.findByKeyword("log");
        assertEquals(2, matches.size());
    }

    @Test
    void findByKeywordCaseInsensitive() {
        ProceduralMemory m = new ProceduralMemory();
        m.add("LOGIN", "x", List.of());
        assertEquals(1, m.findByKeyword("login").size());
    }

    @Test
    void findByKeywordEmpty() {
        ProceduralMemory m = new ProceduralMemory();
        m.add("a", "a", List.of());
        assertTrue(m.findByKeyword("").isEmpty());
        assertTrue(m.findByKeyword(null).isEmpty());
    }

    @Test
    void parametersImmutable() {
        Procedure p = new Procedure("id", "n", "d", List.of(), null, 0);
        assertTrue(p.parameters().isEmpty());
    }

    @Test
    void stepsImmutable() {
        Procedure p = new Procedure("id", "n", "d", null, null, 0);
        assertTrue(p.steps().isEmpty());
    }

    @Test
    void nonNullNameRequired() {
        assertThrows(NullPointerException.class,
            () -> new Procedure("id", null, "d", null, null, 0));
    }
}
