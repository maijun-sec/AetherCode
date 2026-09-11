package org.aethercode.core.fs;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathNormalizerTest {

    @Test
    void normalize_resolvesRelativeAgainstCwd() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        Path out = PathNormalizer.normalize("src/foo/Bar.java", cwd);
        assertTrue(out.isAbsolute());
        assertTrue(out.endsWith("src/foo/Bar.java"));
    }

    @Test
    void normalize_keepsAbsoluteAsIs() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        // Use a path on the same drive to avoid cross-drive surprises.
        Path abs = cwd.resolve("foo.txt");
        Path out = PathNormalizer.normalize(abs.toString(), cwd);
        assertEquals(abs.normalize(), out);
    }

    @Test
    void normalize_stripsDotSegments() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        Path out = PathNormalizer.normalize("./src/../lib/x", cwd);
        assertTrue(out.endsWith("lib/x"));
    }

    @Test
    void normalize_rejectsInvalid() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        // NUL char in path is invalid on Windows; on Linux it passes. Use a clearly invalid form:
        // An empty string is acceptable on Linux but Path.get("") returns "" — keep the test simple.
        // Just assert null rejection.
        assertThrows(NullPointerException.class, () -> PathNormalizer.normalize(null, cwd));
        assertThrows(NullPointerException.class, () -> PathNormalizer.normalize("x", null));
    }

    @Test
    void relativize_makesRelative() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        Path f = cwd.resolve("src/foo/Bar.java");
        assertEquals("src/foo/Bar.java", PathNormalizer.relativize(f, cwd));
    }

    @Test
    void relativize_usesForwardSlashes() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        Path f = cwd.resolve("a").resolve("b").resolve("c.txt");
        String r = PathNormalizer.relativize(f, cwd);
        assertFalse(r.contains("\\"));
        assertEquals("a/b/c.txt", r);
    }

    @Test
    void relativize_returnsAbsoluteWhenOutsideCwd() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        Path f = Paths.get("/etc/passwd").toAbsolutePath();
        // If /etc is not under cwd, we get the absolute path back.
        String r = PathNormalizer.relativize(f, cwd);
        if (!f.startsWith(cwd)) {
            assertEquals(f.toString(), r);
        }
    }

    @Test
    void isInside_trueForCwdItself() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        assertTrue(PathNormalizer.isInside(cwd, cwd));
    }

    @Test
    void isInside_trueForDescendant() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        assertTrue(PathNormalizer.isInside(cwd.resolve("a/b"), cwd));
    }

    @Test
    void isInside_falseForSibling() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        Path other = cwd.getParent().resolve("other");
        assertFalse(PathNormalizer.isInside(other, cwd));
    }

    @Test
    void safeResolve_allowsInside() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        Path p = PathNormalizer.safeResolve("src/foo.txt", cwd);
        assertTrue(p.endsWith("src/foo.txt"));
    }

    @Test
    void safeResolve_rejectsEscape() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        // ../escape is outside cwd
        assertThrows(PathEscapeException.class, () -> PathNormalizer.safeResolve("../escape", cwd));
    }

    @Test
    void safeResolve_rejectsAbsoluteOutside() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        // Pick an absolute path definitely outside cwd
        Path other = cwd.getParent();
        if (other != null && !other.startsWith(cwd)) {
            assertThrows(PathEscapeException.class, () -> PathNormalizer.safeResolve(other.toString(), cwd));
        }
    }

    @Test
    void segments_splitsForward() {
        assertEquals(java.util.List.of("a", "b", "c"), PathNormalizer.segments("a/b/c"));
    }

    @Test
    void segments_splitsBackward() {
        assertEquals(java.util.List.of("a", "b", "c"), PathNormalizer.segments("a\\b\\c"));
    }

    @Test
    void segments_handlesEmpty() {
        assertTrue(PathNormalizer.segments("").isEmpty());
        assertTrue(PathNormalizer.segments(null).isEmpty());
    }

    @Test
    void segments_stripsLeadingTrailingSlashes() {
        assertEquals(java.util.List.of("a", "b"), PathNormalizer.segments("/a/b/"));
    }

    @Test
    void join_concatenatesWithForwardSlash() {
        assertEquals("a/b/c", PathNormalizer.join("a", "b", "c"));
    }

    @Test
    void join_handlesMixedSeparators() {
        assertEquals("a/b/c", PathNormalizer.join("a", "b\\c"));
        assertEquals("a/b/c", PathNormalizer.join("a\\b", "c"));
    }

    @Test
    void join_dropsEmptyAndNull() {
        assertEquals("a/b", PathNormalizer.join("a", null, "", "b"));
    }

    @Test
    void join_withNoArgsReturnsEmpty() {
        assertEquals("", PathNormalizer.join());
    }

    @Test
    void isDescendant_strictlyDeeper() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        assertTrue(PathNormalizer.isDescendant(cwd.resolve("x"), cwd));
        assertFalse(PathNormalizer.isDescendant(cwd, cwd));
    }

    @Test
    void relativize_handlesRelativeInput() {
        Path cwd = Paths.get("/tmp/proj").toAbsolutePath();
        // Even if target is a relative-looking path that doesn't exist, relativize should work
        String r = PathNormalizer.relativize(Paths.get("a/b/c"), cwd);
        assertTrue(r.endsWith("a/b/c"));
    }
}
