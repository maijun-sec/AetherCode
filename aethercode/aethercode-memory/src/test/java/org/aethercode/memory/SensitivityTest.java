package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SensitivityTest {

    @Test
    void parseOrDefaultNullReturnsInternal() {
        assertEquals(Sensitivity.INTERNAL, Sensitivity.parseOrDefault(null));
    }

    @Test
    void parseOrDefaultBlankReturnsInternal() {
        assertEquals(Sensitivity.INTERNAL, Sensitivity.parseOrDefault(""));
        assertEquals(Sensitivity.INTERNAL, Sensitivity.parseOrDefault("   "));
    }

    @Test
    void parseOrDefaultInvalidReturnsInternal() {
        assertEquals(Sensitivity.INTERNAL, Sensitivity.parseOrDefault("nope"));
    }

    @Test
    void parseOrDefaultAllValidValues() {
        assertEquals(Sensitivity.PUBLIC, Sensitivity.parseOrDefault("public"));
        assertEquals(Sensitivity.PUBLIC, Sensitivity.parseOrDefault("PUBLIC"));
        assertEquals(Sensitivity.INTERNAL, Sensitivity.parseOrDefault("internal"));
        assertEquals(Sensitivity.SENSITIVE, Sensitivity.parseOrDefault("Sensitive"));
        assertEquals(Sensitivity.PII, Sensitivity.parseOrDefault("pii"));
    }

    @Test
    void piiAndSensitiveAreHiddenByDefault() {
        assertTrue(Sensitivity.PII.isHiddenByDefault());
        assertTrue(Sensitivity.SENSITIVE.isHiddenByDefault());
        assertFalse(Sensitivity.PUBLIC.isHiddenByDefault());
        assertFalse(Sensitivity.INTERNAL.isHiddenByDefault());
    }

    @Test
    void enumHasFourValues() {
        assertEquals(4, Sensitivity.values().length);
    }
}
