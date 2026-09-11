package org.aethercode.core.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.aethercode.core.config.SchemaValidator;

/**
 * validate a tool's input parameters against its declared
 * {@code inputSchema}. Wraps {@link SchemaValidator} (prior round) with
 * tool-specific affordances: per-parameter error messages with the
 * tool name, a quick {@link #isValid} check, and an integration
 * with the {@link Tool#validateInput} hook.
 */
public class ToolParamValidator {

    public record ValidationResult(boolean valid, List<String> errors) {
        public static ValidationResult ok() { return new ValidationResult(true, List.of()); }
    }

    public static ValidationResult validate(Tool tool, Map<String, Object> input) {
        Objects.requireNonNull(tool, "tool");
        if (input == null) input = Map.of();
        Map<String, Object> schema = tool.inputSchema();
        if (schema == null || schema.isEmpty()) {
            return ValidationResult.ok();
        }
        var schemaResult = SchemaValidator.validate(schema, input);
        List<String> errors = new ArrayList<>();
        for (String e : schemaResult.errors()) {
            errors.add("[" + tool.name() + "] " + e);
        }
        String hookError = tool.validateInput(input);
        if (hookError != null && !hookError.isBlank()) {
            errors.add("[" + tool.name() + "] " + hookError);
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }

    public static boolean isValid(Tool tool, Map<String, Object> input) {
        return validate(tool, input).valid();
    }

    /** a per-tool validator cache (for repeated calls). */
    public static class Cache {
        private final Map<String, Map<String, Object>> schemaByTool = new LinkedHashMap<>();

        public Cache register(Tool tool) {
            schemaByTool.put(tool.name(), tool.inputSchema());
            return this;
        }

        public boolean isValid(String toolName, Map<String, Object> input) {
            Map<String, Object> schema = schemaByTool.get(toolName);
            if (schema == null) throw new IllegalArgumentException("not registered: " + toolName);
            return SchemaValidator.isValid(schema, input);
        }

        public ValidationResult validate(String toolName, Map<String, Object> input) {
            Map<String, Object> schema = schemaByTool.get(toolName);
            if (schema == null) throw new IllegalArgumentException("not registered: " + toolName);
            var r = SchemaValidator.validate(schema, input == null ? Map.of() : input);
            return new ValidationResult(r.valid(), r.errors());
        }

        public Set<String> toolNames() { return schemaByTool.keySet(); }
        public int size() { return schemaByTool.size(); }
    }
}
