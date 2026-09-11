package org.aethercode.evals.verifier;

import org.aethercode.evals.verifier.SchemaVerifier.FieldSpec;
import org.aethercode.evals.verifier.Verifier.Severity;
import org.aethercode.evals.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SchemaVerifier}.
 */
class SchemaVerifierTest {

    /* ----------------------- type matching ----------------------- */

    @Test
    void typeMatchesString() {
        assertTrue(SchemaVerifier.typeMatches("string", "hello"));
        assertTrue(SchemaVerifier.typeMatches("STRING", "x"));
        assertFalse(SchemaVerifier.typeMatches("string", 1));
    }

    @Test
    void typeMatchesInt() {
        assertTrue(SchemaVerifier.typeMatches("int", 1));
        assertTrue(SchemaVerifier.typeMatches("int", 1L));
        assertTrue(SchemaVerifier.typeMatches("integer", 42));
        assertFalse(SchemaVerifier.typeMatches("int", 1.5));
        assertFalse(SchemaVerifier.typeMatches("int", "1"));
    }

    @Test
    void typeMatchesLong() {
        assertTrue(SchemaVerifier.typeMatches("long", 1L));
        assertFalse(SchemaVerifier.typeMatches("long", 1));   // int is not long
    }

    @Test
    void typeMatchesNumber() {
        assertTrue(SchemaVerifier.typeMatches("number", 1));
        assertTrue(SchemaVerifier.typeMatches("number", 1.5));
        assertTrue(SchemaVerifier.typeMatches("double", 3.14));
        assertFalse(SchemaVerifier.typeMatches("number", "x"));
    }

    @Test
    void typeMatchesBoolean() {
        assertTrue(SchemaVerifier.typeMatches("boolean", true));
        assertTrue(SchemaVerifier.typeMatches("bool", false));
        assertFalse(SchemaVerifier.typeMatches("boolean", "true"));
    }

    @Test
    void typeMatchesList() {
        assertTrue(SchemaVerifier.typeMatches("list", List.of(1, 2, 3)));
        assertTrue(SchemaVerifier.typeMatches("array", new int[]{1, 2, 3}));
        assertFalse(SchemaVerifier.typeMatches("list", "1,2,3"));
    }

    @Test
    void typeMatchesMap() {
        assertTrue(SchemaVerifier.typeMatches("map", Map.of("a", 1)));
        assertTrue(SchemaVerifier.typeMatches("object", new LinkedHashMap<>()));
        assertFalse(SchemaVerifier.typeMatches("map", List.of(1, 2)));
    }

    @Test
    void typeMatchesNullTypeIsPermissive() {
        // A null spec type means "no constraint"; useful for opaque fields.
        assertTrue(SchemaVerifier.typeMatches(null, 1));
        assertTrue(SchemaVerifier.typeMatches(null, "x"));
    }

    @Test
    void typeMatchesUnknownTypeIsPermissive() {
        // Forward-compat: a verifier built against a future type that
        // doesn't exist yet shouldn't break the loop.
        assertTrue(SchemaVerifier.typeMatches("custom-type", "anything"));
    }

    /* ----------------------- constructor validation ----------------------- */

    @Test
    void constructorRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaVerifier("", Map.of("a", FieldSpec.required("string"))));
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaVerifier(null, Map.of("a", FieldSpec.required("string"))));
    }

    @Test
    void constructorRejectsEmptySchema() {
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaVerifier("v", new LinkedHashMap<>()));
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaVerifier("v", (Map<String, FieldSpec>) null));
    }

    @Test
    void ofTypeMapAcceptsStringTypeMap() {
        SchemaVerifier v = SchemaVerifier.ofTypeMap("v", Map.of("a", "string", "b", "int"));
        assertNotNull(v);
        assertEquals("v", v.name());
    }

    @Test
    void constructorAcceptsFieldSpecMap() {
        SchemaVerifier v = new SchemaVerifier("v", Map.of(
                "a", FieldSpec.required("string"),
                "b", FieldSpec.optional("int")));
        assertNotNull(v);
    }

    /* ----------------------- verify() ----------------------- */

    @Test
    void verifyPassesWhenAllRequiredFieldsMatch() {
        SchemaVerifier v = new SchemaVerifier("v", Map.of(
                "name", FieldSpec.required("string"),
                "age", FieldSpec.required("int")));
        VerificationResult r = v.verify(Map.of("name", "Alice", "age", 30));
        assertTrue(r.passed());
        assertEquals(Severity.INFO, r.severity());
    }

    @Test
    void verifyFailsOnMissingRequiredField() {
        SchemaVerifier v = new SchemaVerifier("v", Map.of(
                "name", FieldSpec.required("string"),
                "age", FieldSpec.required("int")));
        VerificationResult r = v.verify(Map.of("name", "Alice"));
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
        assertTrue(r.reason().contains("age"), r.reason());
    }

    @Test
    void verifyAcceptsOptionalMissingField() {
        SchemaVerifier v = new SchemaVerifier("v", Map.of(
                "name", FieldSpec.required("string"),
                "nickname", FieldSpec.optional("string")));
        VerificationResult r = v.verify(Map.of("name", "Alice"));
        assertTrue(r.passed());
    }

    @Test
    void verifyFailsOnWrongType() {
        SchemaVerifier v = new SchemaVerifier("v", Map.of("age", FieldSpec.required("int")));
        VerificationResult r = v.verify(Map.of("age", "thirty"));
        assertFalse(r.passed());
        assertTrue(r.reason().contains("wrong type"), r.reason());
        assertTrue(r.reason().contains("age"), r.reason());
    }

    @Test
    void verifyFailsOnNullInput() {
        SchemaVerifier v = new SchemaVerifier("v", Map.of("a", FieldSpec.required("string")));
        VerificationResult r = v.verify(null);
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
        assertTrue(r.reason().contains("null"), r.reason());
    }

    @Test
    void verifySurfacesFailingFieldInMetadata() {
        SchemaVerifier v = new SchemaVerifier("v", Map.of(
                "a", FieldSpec.required("string"),
                "b", FieldSpec.required("int")));
        VerificationResult r = v.verify(Map.of("a", "ok", "b", "not-an-int"));
        assertFalse(r.passed());
        assertEquals("b", r.metadata().get("field"));
        assertEquals("int", r.metadata().get("expected"));
    }

    @Test
    void verifyExtraFieldsAreIgnored() {
        // Verifier is permissive on extras — it only checks declared fields.
        SchemaVerifier v = new SchemaVerifier("v", Map.of("a", FieldSpec.required("string")));
        VerificationResult r = v.verify(Map.of("a", "ok", "extra", 1, "more", "x"));
        assertTrue(r.passed());
    }

    @Test
    void verifyNameAndDescription() {
        SchemaVerifier v = new SchemaVerifier("myverifier", Map.of("a", FieldSpec.required("string")));
        assertEquals("myverifier", v.name());
        assertTrue(v.description().contains("a"), v.description());
    }
}
