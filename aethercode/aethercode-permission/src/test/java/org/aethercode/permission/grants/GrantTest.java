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

package org.aethercode.permission.grants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-201 / T-202: direct unit tests for the {@link Grant} record
 * and {@link GrantsFile} envelope. The integration-style tests
 * (round-trip, atomicity, rotation) live in
 * {@link GrantsStorageTest}.
 */
class GrantTest {

    @Test
    void newId_isHexAndUnique() {
        String a = Grant.newId();
        String b = Grant.newId();
        assertEquals(32, a.length());
        assertEquals(32, b.length());
        assertNotEquals(a, b);
        // Hex only.
        assertTrue(a.matches("[0-9a-f]{32}"));
    }

    @Test
    void grant_equalityIsStructural() {
        Grant a = new Grant("id", GrantScope.PROJECT, "p", "shell.command",
                GrantDecision.ALLOW, "ok", 1L, null);
        Grant b = new Grant("id", GrantScope.PROJECT, "p", "shell.command",
                GrantDecision.ALLOW, "ok", 1L, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void grant_inequalityOnEachField() {
        Grant base = new Grant("id", GrantScope.PROJECT, "p", "shell.command",
                GrantDecision.ALLOW, "ok", 1L, null);
        assertNotEquals(base, new Grant("id2", GrantScope.PROJECT, "p", "shell.command",
                GrantDecision.ALLOW, "ok", 1L, null));
        assertNotEquals(base, new Grant("id", GrantScope.USER, "p", "shell.command",
                GrantDecision.ALLOW, "ok", 1L, null));
        assertNotEquals(base, new Grant("id", GrantScope.PROJECT, "p2", "shell.command",
                GrantDecision.ALLOW, "ok", 1L, null));
        assertNotEquals(base, new Grant("id", GrantScope.PROJECT, "p", "shell.commandX",
                GrantDecision.ALLOW, "ok", 1L, null));
        assertNotEquals(base, new Grant("id", GrantScope.PROJECT, "p", "shell.command",
                GrantDecision.DENY, "ok", 1L, null));
        assertNotEquals(base, new Grant("id", GrantScope.PROJECT, "p", "shell.command",
                GrantDecision.ALLOW, "ok2", 1L, null));
        assertNotEquals(base, new Grant("id", GrantScope.PROJECT, "p", "shell.command",
                GrantDecision.ALLOW, "ok", 2L, null));
        assertNotEquals(base, new Grant("id", GrantScope.PROJECT, "p", "shell.command",
                GrantDecision.ALLOW, "ok", 1L, 100L));
    }

    @Test
    void grantScope_wireValues() {
        assertEquals("session", GrantScope.SESSION.wire());
        assertEquals("project", GrantScope.PROJECT.wire());
        assertEquals("user", GrantScope.USER.wire());
    }

    @Test
    void grantDecision_wireValues() {
        assertEquals("allow", GrantDecision.ALLOW.wire());
        assertEquals("deny", GrantDecision.DENY.wire());
    }

    @Test
    void grantsFile_currentSchemaVersion() {
        assertEquals(1, GrantsFile.CURRENT_SCHEMA_VERSION);
        assertEquals("grants.json", GrantsFile.FILE_NAME);
    }

    @Test
    void grantsFile_emptyHasZeroGrants() {
        GrantsFile f = GrantsFile.empty();
        assertEquals(1, f.schemaVersion());
        assertNotNull(f.grants());
        assertTrue(f.grants().isEmpty());
    }

    @Test
    void grantsFile_appendReturnsNewInstance() {
        GrantsFile a = GrantsFile.empty();
        Grant g = Grant.create(GrantScope.PROJECT, "p", "x", GrantDecision.ALLOW, "r", null);
        GrantsFile b = a.append(g);
        // Append is pure — original unchanged.
        assertTrue(a.grants().isEmpty());
        assertEquals(1, b.grants().size());
    }

    @Test
    void grantsFile_rejectsNullGrants() {
        assertThrows(NullPointerException.class, () -> new GrantsFile(1, null));
    }

    @Test
    void grantsFile_filtersNullEntries() {
        Grant g = Grant.create(GrantScope.PROJECT, "p", "x", GrantDecision.ALLOW, "r", null);
        GrantsFile f = new GrantsFile(1, java.util.Arrays.asList(g, null));
        assertEquals(1, f.grants().size());
    }

    @Test
    void grant_createDefaultsReasonWhenNull() {
        Grant g = Grant.create(GrantScope.PROJECT, "p", "x", GrantDecision.ALLOW, null, null);
        assertEquals("", g.reason());
    }

    @Test
    void grant_isExpired_handlesBoundary() {
        Grant g = new Grant("id", GrantScope.PROJECT, "p", "x",
                GrantDecision.ALLOW, "r", 1L, 100L);
        // exactly at expiresAt == expired
        assertTrue(g.isExpired(100L));
        // one ms before == not expired
        assertFalse(g.isExpired(99L));
    }

    private static void assertThrows(Class<? extends Throwable> expected, Runnable r) {
        try {
            r.run();
            org.junit.jupiter.api.Assertions.fail("expected " + expected.getName());
        } catch (Throwable t) {
            if (!expected.isInstance(t)) {
                org.junit.jupiter.api.Assertions.fail(
                        "expected " + expected.getName() + " but got " + t.getClass().getName());
            }
        }
    }
}
