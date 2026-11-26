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

package org.aethercode.core.consistency;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R415: what counts as a value-bearing declaration.
 *
 * <p>★ WHAT THIS PINS ★ the slot set is the SCOPE of the whole
 * consistency check. Too wide and the check reports noise the user cannot
 * act on; too narrow and it misses the contract the user cares about.
 */
class DeclarationSlotsR415Test {

    private static final Path JAVA = Path.of("src/AppConfig.java");
    private static final Path PROPS = Path.of("config/app.properties");

    @Test
    void aJavaConstantIsASlot() {
        Map<String, DeclarationSlots.Slot> slots =
                DeclarationSlots.parse(JAVA, "public static final int MAX_RETRIES = 3;\n");
        assertThat(slots).containsKey("MAX_RETRIES");
        assertThat(slots.get("MAX_RETRIES").value()).isEqualTo("3");
        assertThat(slots.get("MAX_RETRIES").line()).isEqualTo(1);
    }

    @Test
    void aTypedConstantWithoutModifiersIsASlot() {
        Map<String, DeclarationSlots.Slot> slots =
                DeclarationSlots.parse(JAVA, "    int MAX_RETRIES = 3;\n");
        assertThat(slots.get("MAX_RETRIES").value()).isEqualTo("3");
    }

    @Test
    void anExportedConstIsASlot() {
        Map<String, DeclarationSlots.Slot> slots =
                DeclarationSlots.parse(Path.of("src/c.js"), "export const MAX_RETRIES = 3;\n");
        assertThat(slots.get("MAX_RETRIES").value()).isEqualTo("3");
    }

    @Test
    void aPythonConstantIsASlot() {
        Map<String, DeclarationSlots.Slot> slots =
                DeclarationSlots.parse(Path.of("app.py"), "MAX_RETRIES = 3\n");
        assertThat(slots.get("MAX_RETRIES").value()).isEqualTo("3");
    }

    @Test
    void aCDefineIsASlot() {
        Map<String, DeclarationSlots.Slot> slots =
                DeclarationSlots.parse(Path.of("app.h"), "#define MAX_RETRIES 3\n");
        assertThat(slots.get("MAX_RETRIES").value()).isEqualTo("3");
    }

    @Test
    void aComparisonIsNotASlot() {
        // ★ THE `==` GUARD ★ without it, "if (X == 1)" parses as "the
        // constant X has the value = 1" — a comparison masquerading as a
        // declaration.
        Map<String, DeclarationSlots.Slot> slots =
                DeclarationSlots.parse(JAVA, "        if (MAX_RETRIES == 1) {\n");
        assertThat(slots).isEmpty();
    }

    @Test
    void aLowerCaseAssignmentIsNotASlot() {
        // ★ ONLY CONSTANTS ARE CONTRACTS ★ every line of code has
        // assignments; the constants are the ones that must agree.
        Map<String, DeclarationSlots.Slot> slots =
                DeclarationSlots.parse(JAVA, "        int retries = 3;\n");
        assertThat(slots).isEmpty();
    }

    @Test
    void aMethodCallIsNotASlot() {
        Map<String, DeclarationSlots.Slot> slots =
                DeclarationSlots.parse(JAVA, "        assertEquals(MAX_RETRIES, 3);\n");
        assertThat(slots).isEmpty();
    }

    @Test
    void aConfigKeyIsASlotInAConfigFile() {
        Map<String, DeclarationSlots.Slot> slots =
                DeclarationSlots.parse(PROPS, "max.retries=3\n");
        assertThat(slots.get("max.retries").value()).isEqualTo("3");
    }

    @Test
    void aConfigKeyIsNotASlotInSourceCode() {
        // ★ THE FILE DECIDES ★ in source code "url = ..." is an assignment
        // to a local; in a properties file it is a configuration contract.
        Map<String, DeclarationSlots.Slot> slots =
                DeclarationSlots.parse(JAVA, "        url = \"http://x\";\n");
        assertThat(slots).isEmpty();
    }

    @Test
    void theValueIsNormalized() {
        // ★ THE SEMICOLON IS NOT PART OF THE VALUE ★ and neither is the
        // whitespace inside it, or two copies would never compare equal.
        Map<String, DeclarationSlots.Slot> slots =
                DeclarationSlots.parse(JAVA, "public static final int MAX_RETRIES   =   3 ;\n");
        assertThat(slots.get("MAX_RETRIES").value()).isEqualTo("3");
    }

    @Test
    void theFirstDeclarationWins() {
        Map<String, DeclarationSlots.Slot> slots =
                DeclarationSlots.parse(JAVA, "int MAX_RETRIES = 3;\nint MAX_RETRIES = 7;\n");
        assertThat(slots.get("MAX_RETRIES").value()).isEqualTo("3");
        assertThat(slots.get("MAX_RETRIES").line()).isEqualTo(1);
    }

    @Test
    void anEmptyFileHasNoSlots() {
        assertThat(DeclarationSlots.parse(JAVA, "")).isEmpty();
        assertThat(DeclarationSlots.parse(JAVA, null)).isEmpty();
    }
}
