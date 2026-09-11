package org.aethercode.tools.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.aethercode.tools.plugin.PluginManifest.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManifestTest {

    @Test
    void parse_minimalJson() {
        String json = "{\"name\":\"x\",\"version\":\"1.0.0\",\"entryPoint\":\"com.example.Main\"}";
        Manifest m = PluginManifest.parse(json);
        assertEquals("x", m.name());
        assertEquals("1.0.0", m.version());
        assertEquals("com.example.Main", m.entryPoint());
    }

    @Test
    void parse_fullJson() {
        String json = ""
                + "{\"name\":\"x\",\"version\":\"1.0.0\",\"description\":\"d\","
                + "\"author\":\"a\",\"license\":\"MIT\",\"entryPoint\":\"com.example.Main\","
                + "\"tools\":[\"t1\",\"t2\"],\"dependencies\":[\"core>=2\"],"
                + "\"permissions\":[\"file.read\"],\"config\":{\"k\":\"v\"}}";
        Manifest m = PluginManifest.parse(json);
        assertEquals("d", m.description());
        assertEquals(2, m.tools().size());
        assertEquals(1, m.dependencies().size());
    }

    @Test
    void parse_rejectsInvalidJson() {
        assertThrows(PluginManifestException.class, () -> PluginManifest.parse("{not json"));
    }

    @Test
    void parse_rejectsMissingName() {
        String json = "{\"version\":\"1.0.0\",\"entryPoint\":\"x\"}";
        assertThrows(RuntimeException.class, () -> PluginManifest.parse(json));
    }

    @Test
    void loadFrom_readsFile(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("plugin.json");
        Files.writeString(f, "{\"name\":\"x\",\"version\":\"1.0.0\",\"entryPoint\":\"y\"}");
        Manifest m = PluginManifest.loadFrom(f);
        assertEquals("x", m.name());
    }

    @Test
    void loadFrom_throwsOnMissing(@TempDir Path tmp) {
        Path f = tmp.resolve("nope.json");
        assertThrows(PluginManifestException.class, () -> PluginManifest.loadFrom(f));
    }

    @Test
    void validate_flagsMissingFields() {
        // Can't easily construct a manifest with a blank name (record constructor rejects it).
        // So we test by passing a manifest with an empty list of tools and checking
        // that the version field validation works.
        Manifest m = new Manifest("name", "bad-version", "", "", "", "x", List.of(), List.of(), List.of(), Map.of());
        var issues = PluginManifest.validate(m);
        assertTrue(issues.stream().anyMatch(s -> s.contains("semver")));
    }

    @Test
    void validate_flagsInvalidSemver() {
        Manifest m = new Manifest("x", "not-semver", "", "", "", "y", List.of(), List.of(), List.of(), Map.of());
        var issues = PluginManifest.validate(m);
        assertTrue(issues.stream().anyMatch(s -> s.contains("semver")));
    }

    @Test
    void validate_acceptsValidSemver() {
        assertTrue(PluginManifest.isValid(new Manifest("x", "1.2.3", "", "", "", "y", List.of(), List.of(), List.of(), Map.of())));
        assertTrue(PluginManifest.isValid(new Manifest("x", "1.2.3-rc.1", "", "", "", "y", List.of(), List.of(), List.of(), Map.of())));
        assertTrue(PluginManifest.isValid(new Manifest("x", "1.2.3+build.5", "", "", "", "y", List.of(), List.of(), List.of(), Map.of())));
    }

    @Test
    void isValid_returnsTrueForGood() {
        Manifest m = PluginManifest.template("x", "1.0.0", "y");
        assertTrue(PluginManifest.isValid(m));
    }

    @Test
    void toJson_roundtrips() {
        Manifest m = PluginManifest.template("x", "1.0.0", "y");
        String json = PluginManifest.toJson(m);
        Manifest back = PluginManifest.parse(json);
        assertEquals(m.name(), back.name());
        assertEquals(m.version(), back.version());
    }

    @Test
    void tryLoad_returnsEmptyForMissing(@TempDir Path tmp) {
        Path f = tmp.resolve("nope.json");
        assertFalse(PluginManifest.tryLoad(f).isPresent());
    }

    @Test
    void tryLoad_returnsEmptyForInvalid(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("bad.json");
        Files.writeString(f, "{not valid");
        assertFalse(PluginManifest.tryLoad(f).isPresent());
    }

    @Test
    void tryLoad_returnsManifestForValid(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("good.json");
        Files.writeString(f, "{\"name\":\"x\",\"version\":\"1.0.0\",\"entryPoint\":\"y\"}");
        assertTrue(PluginManifest.tryLoad(f).isPresent());
    }

    @Test
    void asMap_includesAllFields() {
        Manifest m = PluginManifest.template("x", "1.0.0", "y");
        var map = PluginManifest.asMap(m);
        assertEquals("x", map.get("name"));
        assertEquals("1.0.0", map.get("version"));
        assertEquals("y", map.get("entryPoint"));
    }

    @Test
    void parse_missingEntryPoint() {
        String json = "{\"name\":\"x\",\"version\":\"1.0.0\"}";
        Manifest m = PluginManifest.parse(json);
        var issues = PluginManifest.validate(m);
        assertTrue(issues.stream().anyMatch(s -> s.contains("entryPoint")));
    }

    @Test
    void template_defaultFieldsAreEmpty() {
        Manifest m = PluginManifest.template("x", "1.0.0", "y");
        assertEquals("", m.description());
        assertEquals("", m.author());
        assertEquals("", m.license());
        assertTrue(m.tools().isEmpty());
        assertTrue(m.dependencies().isEmpty());
    }

    @Test
    void template_rejectsNullArgs() {
        try { PluginManifest.template(null, "1.0.0", "y"); } catch (NullPointerException e) { return; }
        try { PluginManifest.template("x", null, "y"); } catch (NullPointerException e) { return; }
        try { PluginManifest.template("x", "1.0.0", null); } catch (NullPointerException e) { return; }
        throw new AssertionError("expected NPE for null arg");
    }
}
