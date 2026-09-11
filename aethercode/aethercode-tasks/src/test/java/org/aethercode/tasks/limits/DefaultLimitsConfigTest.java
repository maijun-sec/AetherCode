package org.aethercode.tasks.limits;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R240 (O-5): tests for {@link DefaultLimitsConfig}. The class is
 * small and tolerant, so the tests are mostly "did the parser
 * return what I expect" plus the "malformed input is logged and
 * ignored" guarantee.
 */
class DefaultLimitsConfigTest {

    @Test
    void missingFileReturnsUnlimited(@TempDir Path tmp) {
        Limits got = DefaultLimitsConfig.loadFrom(tmp.resolve("does-not-exist"));
        assertNotNull(got);
        assertTrue(got.isUnlimited(), "missing file must yield unlimited");
    }

    @Test
    void emptyFileReturnsUnlimited(@TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("task-defaults.yaml");
        Files.writeString(p, "", StandardCharsets.UTF_8);
        Limits got = DefaultLimitsConfig.loadFrom(p);
        assertTrue(got.isUnlimited());
    }

    @Test
    void fullFileParsesAllFields(@TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("task-defaults.yaml");
        Files.writeString(p,
                "# a comment\n"
                        + "\n"
                        + "wallClockMs: 600000\n"
                        + "tokens:      50000\n"
                        + "calls:       200\n"
                        + "fileWrites:  50\n"
                        + "network:     100\n",
                StandardCharsets.UTF_8);
        Limits got = DefaultLimitsConfig.loadFrom(p);
        assertEquals(600_000L, got.wallClockMs());
        assertEquals(50_000L,  got.tokens());
        assertEquals(200L,     got.calls());
        assertEquals(50L,      got.fileWrites());
        assertEquals(100L,     got.network());
    }

    @Test
    void partialFileLeavesMissingFieldsNull(@TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("task-defaults.yaml");
        Files.writeString(p, "tokens: 12345\n", StandardCharsets.UTF_8);
        Limits got = DefaultLimitsConfig.loadFrom(p);
        assertEquals(12_345L, got.tokens());
        assertNull(got.wallClockMs());
        assertNull(got.calls());
        assertNull(got.fileWrites());
        assertNull(got.network());
        assertTrue(!got.isUnlimited(), "partial file is not unlimited");
    }

    @Test
    void unknownKeyIsIgnored(@TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("task-defaults.yaml");
        Files.writeString(p,
                "tokens: 8000\n"
                        + "futureField: 9999\n",
                StandardCharsets.UTF_8);
        Limits got = DefaultLimitsConfig.loadFrom(p);
        assertEquals(8_000L, got.tokens());
    }

    @Test
    void badValueIsIgnored(@TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("task-defaults.yaml");
        Files.writeString(p,
                "tokens: not-a-number\n"
                        + "calls: 42\n",
                StandardCharsets.UTF_8);
        Limits got = DefaultLimitsConfig.loadFrom(p);
        assertNull(got.tokens());
        assertEquals(42L, got.calls());
    }

    @Test
    void roundTripYaml() {
        Limits src = DefaultLimitsConfig.example();
        String yaml = DefaultLimitsConfig.toYaml(src);
        // Re-parse the rendered YAML by writing it to a temp
        // file. This catches both "the field is present" and
        // "the format is what the parser expects".
        Path tmp = java.nio.file.Paths.get(
                System.getProperty("java.io.tmpdir"),
                "default-limits-roundtrip-" + System.nanoTime() + ".yaml");
        try {
            Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
            Limits back = DefaultLimitsConfig.loadFrom(tmp);
            assertEquals(src.wallClockMs(), back.wallClockMs());
            assertEquals(src.tokens(),      back.tokens());
            assertEquals(src.calls(),       back.calls());
            assertEquals(src.fileWrites(),  back.fileWrites());
            assertEquals(src.network(),     back.network());
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }
}
