package org.aethercode.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * a tiny, self-contained JSON-Schema-like validator. Covers the
 * subset we actually need for {@code .aethercode/settings.json}:
 * {@code type}, {@code required}, {@code properties}, {@code enum},
 * {@code pattern}, {@code minLength}, {@code maxLength}, {@code minimum},
 * {@code maximum}, {@code items}, and basic {@code oneOf}.
 *
 * <p>The schema itself is a {@code Map<String, Object>} (parsed JSON),
 * so the validator works without any external schema library.
 */
public final class SchemaValidator {

    private SchemaValidator() {}

    public record ValidationResult(boolean valid, List<String> errors) {
        public static ValidationResult ok() { return new ValidationResult(true, List.of()); }
        public static ValidationResult of(List<String> errs) {
            return errs.isEmpty() ? ok() : new ValidationResult(false, List.copyOf(errs));
        }
    }

    public static ValidationResult validate(Object schema, Object value) {
        List<String> errors = new ArrayList<>();
        validateNode("", schema, value, errors);
        return ValidationResult.of(errors);
    }

    /** validate a single sub-schema without polluting the parent's error list. */
    private static boolean subValidate(Object schema, Object value) {
        List<String> errs = new ArrayList<>();
        validateNode("", schema, value, errs);
        return errs.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static void validateNode(String path, Object schema, Object value, List<String> errors) {
        if (schema instanceof Map<?, ?> schemaMap) {
            Map<String, Object> s = (Map<String, Object>) schemaMap;
            // type
            Object typeSpec = s.get("type");
            if (typeSpec instanceof String type) {
                if (!matchesType(type, value)) {
                    errors.add(path + ": expected type " + type + " but got " + describeType(value));
                    return; // stop further checks for this node
                }
            }
            // required + properties
            Object required = s.get("required");
            Object properties = s.get("properties");
            if (properties instanceof Map<?, ?> props && value instanceof Map<?, ?> valMap) {
                Map<String, Object> val = (Map<String, Object>) valMap;
                for (Map.Entry<?, ?> e : props.entrySet()) {
                    String name = e.getKey().toString();
                    Object subSchema = e.getValue();
                    Object subVal = val.get(name);
                    String subPath = path.isEmpty() ? name : path + "." + name;
                    if (subVal == null) {
                        if (isRequired(required, name)) {
                            errors.add(subPath + ": is required but missing");
                        }
                    } else {
                        validateNode(subPath, subSchema, subVal, errors);
                    }
                }
            }
            // Required check even when properties is absent (a schema can require keys
            // without re-declaring their types). When properties is present, the loop
            // above already covers required-key absence for known keys, so skip here
            // to avoid double-reporting.
            if (properties == null && value instanceof Map<?, ?> valMap2) {
                for (Object req : requiredList(required)) {
                    if (!valMap2.containsKey(req)) {
                        String subPath = path.isEmpty() ? req.toString() : path + "." + req;
                        errors.add(subPath + ": is required but missing");
                    }
                }
            }
            // enum
            Object enumSpec = s.get("enum");
            if (enumSpec instanceof List<?> enums) {
                boolean found = false;
                for (Object e : enums) if (Objects.equals(e, value)) { found = true; break; }
                if (!found) {
                    errors.add(path + ": value " + value + " is not in enum " + enums);
                }
            }
            // pattern (string)
            Object patternSpec = s.get("pattern");
            if (patternSpec instanceof String pat && value instanceof String sv) {
                if (!Pattern.compile(pat).matcher(sv).matches()) {
                    errors.add(path + ": value '" + sv + "' does not match pattern " + pat);
                }
            }
            // minLength / maxLength
            Object minLen = s.get("minLength");
            Object maxLen = s.get("maxLength");
            if (value instanceof String sv2) {
                if (minLen instanceof Number mn && sv2.length() < mn.intValue()) {
                    errors.add(path + ": string length " + sv2.length() + " < minLength " + mn.intValue());
                }
                if (maxLen instanceof Number mx && sv2.length() > mx.intValue()) {
                    errors.add(path + ": string length " + sv2.length() + " > maxLength " + mx.intValue());
                }
            }
            // minimum / maximum
            Object minN = s.get("minimum");
            Object maxN = s.get("maximum");
            if (value instanceof Number nv) {
                double v = nv.doubleValue();
                if (minN instanceof Number mn && v < mn.doubleValue()) {
                    errors.add(path + ": value " + v + " < minimum " + mn.doubleValue());
                }
                if (maxN instanceof Number mx && v > mx.doubleValue()) {
                    errors.add(path + ": value " + v + " > maximum " + mx.doubleValue());
                }
            }
            // items (array element schema)
            Object items = s.get("items");
            if (items != null && value instanceof List<?> valueList) {
                for (int i = 0; i < valueList.size(); i++) {
                    validateNode(path + "[" + i + "]", items, valueList.get(i), errors);
                }
            }
            // oneOf
            Object oneOf = s.get("oneOf");
            if (oneOf instanceof List<?> schemas) {
                int matches = 0;
                for (Object sub : schemas) {
                    if (subValidate(sub, value)) matches++;
                }
                if (matches != 1) {
                    errors.add(path + ": oneOf requires exactly 1 match, got " + matches);
                }
            }
        } else if (schema instanceof List<?> schemaList) {
            // tuple validation: each item in the list is a schema for the corresponding item
            if (value instanceof List<?> valueList) {
                for (int i = 0; i < schemaList.size() && i < valueList.size(); i++) {
                    validateNode(path + "[" + i + "]", schemaList.get(i), valueList.get(i), errors);
                }
            }
        }
        // primitive schema (Boolean etc) is treated as a no-op
    }

    private static boolean matchesType(String type, Object value) {
        return switch (type) {
            case "string"  -> value instanceof String;
            case "number"  -> value instanceof Number;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "boolean" -> value instanceof Boolean;
            case "array"   -> value instanceof List;
            case "object"  -> value instanceof Map;
            case "null"    -> value == null;
            default        -> true;
        };
    }

    private static String describeType(Object v) {
        if (v == null) return "null";
        if (v instanceof String)  return "string";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Integer || v instanceof Long) return "integer";
        if (v instanceof Number)  return "number";
        if (v instanceof List)    return "array";
        if (v instanceof Map)     return "object";
        return v.getClass().getSimpleName();
    }

    private static boolean isRequired(Object required, String name) {
        if (required instanceof List<?> reqs) {
            for (Object r : reqs) if (Objects.equals(r, name)) return true;
        }
        return false;
    }

    private static List<?> requiredList(Object required) {
        if (required instanceof List<?> reqs) return reqs;
        return List.of();
    }

    /** convenience: returns true iff the value validates. */
    public static boolean isValid(Object schema, Object value) {
        return validate(schema, value).valid();
    }

    /** build a small schema that requires a string field. */
    public static Map<String, Object> stringSchema() {
        return Map.of("type", "string");
    }

    public static Map<String, Object> requiredString() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("minLength", 1);
        return m;
    }
}
