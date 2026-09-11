package org.aethercode.core.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aethercode.core.config.SchemaValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaValidatorTest {

    @Test
    void validate_primitiveString() {
        ValidationResult r = SchemaValidator.validate(Map.of("type", "string"), "hello");
        assertTrue(r.valid());
    }

    @Test
    void validate_typeMismatch() {
        ValidationResult r = SchemaValidator.validate(Map.of("type", "string"), 42);
        assertFalse(r.valid());
        assertEquals(1, r.errors().size());
    }

    @Test
    void validate_requiredMissing() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("name"),
                "properties", Map.of("name", Map.of("type", "string"))
        );
        ValidationResult r = SchemaValidator.validate(schema, Map.of("other", "x"));
        assertFalse(r.valid());
        assertTrue(r.errors().get(0).contains("required"));
    }

    @Test
    void validate_requiredPresent() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("name"),
                "properties", Map.of("name", Map.of("type", "string"))
        );
        ValidationResult r = SchemaValidator.validate(schema, Map.of("name", "alice"));
        assertTrue(r.valid());
    }

    @Test
    void validate_enum() {
        Map<String, Object> schema = Map.of(
                "type", "string",
                "enum", List.of("a", "b", "c")
        );
        assertTrue(SchemaValidator.validate(schema, "a").valid());
        assertFalse(SchemaValidator.validate(schema, "z").valid());
    }

    @Test
    void validate_pattern() {
        Map<String, Object> schema = Map.of(
                "type", "string",
                "pattern", "^[a-z]+$"
        );
        assertTrue(SchemaValidator.validate(schema, "abc").valid());
        assertFalse(SchemaValidator.validate(schema, "ABC").valid());
    }

    @Test
    void validate_minMaxLength() {
        Map<String, Object> schema = Map.of(
                "type", "string",
                "minLength", 3,
                "maxLength", 5
        );
        assertFalse(SchemaValidator.validate(schema, "ab").valid());
        assertTrue(SchemaValidator.validate(schema, "abc").valid());
        assertTrue(SchemaValidator.validate(schema, "abcde").valid());
        assertFalse(SchemaValidator.validate(schema, "abcdef").valid());
    }

    @Test
    void validate_minimumMaximum() {
        Map<String, Object> schema = Map.of(
                "type", "number",
                "minimum", 0,
                "maximum", 100
        );
        assertFalse(SchemaValidator.validate(schema, -1).valid());
        assertTrue(SchemaValidator.validate(schema, 50).valid());
        assertFalse(SchemaValidator.validate(schema, 101).valid());
    }

    @Test
    void validate_integerType() {
        assertTrue(SchemaValidator.validate(Map.of("type", "integer"), 5L).valid());
        assertFalse(SchemaValidator.validate(Map.of("type", "integer"), 5.5).valid());
    }

    @Test
    void validate_arrayOfStrings() {
        Map<String, Object> schema = Map.of(
                "type", "array",
                "items", Map.of("type", "string")
        );
        assertTrue(SchemaValidator.validate(schema, List.of("a", "b")).valid());
        assertFalse(SchemaValidator.validate(schema, List.of("a", 5)).valid());
    }

    @Test
    void validate_nestedObject() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "user", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "age", Map.of("type", "integer", "minimum", 0)
                                )
                        )
                )
        );
        assertTrue(SchemaValidator.validate(schema, Map.of("user", Map.of("age", 30))).valid());
        assertFalse(SchemaValidator.validate(schema, Map.of("user", Map.of("age", -1))).valid());
    }

    @Test
    void validate_oneOf() {
        Map<String, Object> schema = Map.of(
                "oneOf", List.of(
                        Map.of("type", "string"),
                        Map.of("type", "number")
                )
        );
        assertTrue(SchemaValidator.validate(schema, "hello").valid());
        assertTrue(SchemaValidator.validate(schema, 42).valid());
        assertFalse(SchemaValidator.validate(schema, true).valid());
    }

    @Test
    void validate_oneOfWithMultipleMatches() {
        Map<String, Object> schema = Map.of(
                "oneOf", List.of(
                        Map.of("type", "string"),
                        Map.of("minLength", 0)  // matches both
                )
        );
        // "hello" matches both string type and minLength 0 — exactly 1 required
        assertFalse(SchemaValidator.validate(schema, "hello").valid());
    }

    @Test
    void validate_nullAllowedWhenTypeIsNull() {
        assertTrue(SchemaValidator.validate(Map.of("type", "null"), null).valid());
    }

    @Test
    void validate_booleanType() {
        assertTrue(SchemaValidator.validate(Map.of("type", "boolean"), true).valid());
        assertFalse(SchemaValidator.validate(Map.of("type", "boolean"), "true").valid());
    }

    @Test
    void isValid_shorthand() {
        assertTrue(SchemaValidator.isValid(Map.of("type", "string"), "x"));
        assertFalse(SchemaValidator.isValid(Map.of("type", "string"), 1));
    }

    @Test
    void stringSchema_factory() {
        assertTrue(SchemaValidator.isValid(SchemaValidator.stringSchema(), "hi"));
    }

    @Test
    void requiredString_factory() {
        assertTrue(SchemaValidator.isValid(SchemaValidator.requiredString(), "x"));
        assertFalse(SchemaValidator.isValid(SchemaValidator.requiredString(), ""));
    }

    @Test
    void validate_emptySchemaAcceptsEverything() {
        // Empty schema is treated as a Map with no constraints
        assertTrue(SchemaValidator.validate(Map.of(), "anything").valid());
        assertTrue(SchemaValidator.validate(Map.of(), 42).valid());
        assertTrue(SchemaValidator.validate(Map.of(), null).valid());
    }

    @Test
    void validate_multipleErrorsAccumulate() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("a", "b"),
                "properties", Map.of(
                        "a", Map.of("type", "string"),
                        "b", Map.of("type", "string")
                )
        );
        ValidationResult r = SchemaValidator.validate(schema, Map.of());
        assertFalse(r.valid());
        assertEquals(2, r.errors().size());
    }

    @Test
    void validate_nestedErrorsIncludePath() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "outer", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "inner", Map.of("type", "integer")
                                )
                        )
                )
        );
        ValidationResult r = SchemaValidator.validate(schema, Map.of("outer", Map.of("inner", "notanint")));
        assertFalse(r.valid());
        assertTrue(r.errors().get(0).contains("outer.inner"));
    }

    @Test
    void validate_objectPropertyWithoutTypeStillValidates() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("a", Map.of())
        );
        // a is required by presence but has no constraints
        assertTrue(SchemaValidator.validate(schema, Map.of("a", "anything")).valid());
    }

    @Test
    void validate_tupleSchema() {
        List<Object> schema = List.of(
                Map.of("type", "string"),
                Map.of("type", "integer")
        );
        // schema is a list — each element validates the corresponding value
        ValidationResult r = SchemaValidator.validate(schema, List.of("x", 5));
        assertTrue(r.valid());
        ValidationResult r2 = SchemaValidator.validate(schema, List.of("x", "y"));
        assertFalse(r2.valid());
    }
}
