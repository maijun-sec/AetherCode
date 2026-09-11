package org.aethercode.tools.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * parse an {@code .aethercode-plugin.json} file. The
 * manifest declares the plugin's identity, version, capabilities,
 * and a list of tool names it contributes. Used by the plugin
 * loader (prior round) to install third-party tools without restart.
 */
public final class PluginManifest {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Manifest(
            @JsonProperty("name")        String name,
            @JsonProperty("version")     String version,
            @JsonProperty("description") String description,
            @JsonProperty("author")      String author,
            @JsonProperty("license")     String license,
            @JsonProperty("entryPoint")  String entryPoint,
            @JsonProperty("tools")       List<String> tools,
            @JsonProperty("dependencies") List<String> dependencies,
            @JsonProperty("permissions") List<String> permissions,
            @JsonProperty("config")      Map<String, Object> config
    ) {
        public Manifest {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
            tools = tools == null ? List.of() : List.copyOf(tools);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
            config = config == null ? Map.of() : Map.copyOf(config);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PluginManifest() {}

    /** parse a JSON string into a manifest. Throws on invalid JSON. */
    public static Manifest parse(String json) {
        try {
            return MAPPER.readValue(json, Manifest.class);
        } catch (IOException e) {
            throw new PluginManifestException("failed to parse manifest: " + e.getMessage(), e);
        }
    }

    /** load and parse a manifest from a file path. */
    public static Manifest loadFrom(Path file) {
        try {
            return parse(Files.readString(file));
        } catch (IOException e) {
            throw new PluginManifestException("failed to read manifest: " + e.getMessage(), e);
        }
    }

    /** validate a parsed manifest. Returns the list of issues (empty if valid). */
    public static List<String> validate(Manifest m) {
        java.util.ArrayList<String> issues = new java.util.ArrayList<>();
        if (m == null) { issues.add("manifest is null"); return issues; }
        if (m.name() == null || m.name().isBlank()) issues.add("name is required");
        if (m.version() == null || m.version().isBlank()) issues.add("version is required");
        if (m.entryPoint() == null || m.entryPoint().isBlank()) issues.add("entryPoint is required");
        if (!isValidSemVer(m.version())) issues.add("version must be semver (e.g. 1.2.3): " + m.version());
        return issues;
    }

    private static boolean isValidSemVer(String v) {
        if (v == null) return false;
        // Simple major.minor.patch check, allow optional -suffix
        return v.matches("^\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.-]+)?(\\+[A-Za-z0-9.-]+)?$");
    }

    public static boolean isValid(Manifest m) { return validate(m).isEmpty(); }

    /** render a manifest as JSON. */
    public static String toJson(Manifest m) {
        try { return MAPPER.writeValueAsString(m); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    /** a one-stop helper: load + validate. */
    public static java.util.Optional<Manifest> tryLoad(Path file) {
        if (!Files.exists(file)) return java.util.Optional.empty();
        try {
            Manifest m = loadFrom(file);
            if (isValid(m)) return java.util.Optional.of(m);
        } catch (PluginManifestException ignored) {}
        return java.util.Optional.empty();
    }

    /** a minimal manifest template for new plugins. */
    public static Manifest template(String name, String version, String entryPoint) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(entryPoint, "entryPoint");
        return new Manifest(name, version, "", "", "", entryPoint, List.of(), List.of(), List.of(), Map.of());
    }

    public static Map<String, Object> asMap(Manifest m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", m.name());
        out.put("version", m.version());
        out.put("description", m.description());
        out.put("author", m.author());
        out.put("license", m.license());
        out.put("entryPoint", m.entryPoint());
        out.put("tools", m.tools());
        out.put("dependencies", m.dependencies());
        out.put("permissions", m.permissions());
        out.put("config", m.config());
        return out;
    }
}
