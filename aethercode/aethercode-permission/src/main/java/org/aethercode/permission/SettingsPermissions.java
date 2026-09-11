package org.aethercode.permission;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Parsed {@code permissions} block of a {@code settings.json}. Three buckets:
 * allow, deny, ask. Each bucket is a list of {@link Rule}s.
 *
 * <p>Resolution order (matching the TS original):
 * <ol>
 *   <li>deny rules — first match wins, deny beats everything</li>
 *   <li>ask rules — first match wins</li>
 *   <li>allow rules — first match wins</li>
 *   <li>fall back to {@link org.aethercode.core.permission.PermissionMode}</li>
 * </ol>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SettingsPermissions {
    public List<Rule> allow = List.of();
    public List<Rule> deny  = List.of();
    public List<Rule> ask   = List.of();

    public static SettingsPermissions empty() { return new SettingsPermissions(); }

    @SuppressWarnings("unchecked")
    public static SettingsPermissions fromMap(Map<String, Object> m) {
        SettingsPermissions sp = new SettingsPermissions();
        if (m == null) return sp;
        sp.allow = Rule.fromList((List<Map<String, Object>>) m.get("allow"));
        sp.deny  = Rule.fromList((List<Map<String, Object>>) m.get("deny"));
        sp.ask   = Rule.fromList((List<Map<String, Object>>) m.get("ask"));
        return sp;
    }

    public static SettingsPermissions loadFrom(Path file) {
        if (!Files.exists(file)) return empty();
        try {
            Map<String, Object> raw = new ObjectMapper().readValue(Files.readString(file), Map.class);
            Object perms = raw.get("permissions");
            return fromMap(perms instanceof Map ? (Map<String, Object>) perms : null);
        } catch (IOException e) {
            return empty();
        }
    }
}
