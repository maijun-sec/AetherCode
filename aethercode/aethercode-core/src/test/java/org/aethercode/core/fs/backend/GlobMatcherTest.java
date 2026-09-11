package org.aethercode.core.fs.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for the wcmatch-style {@link GlobMatcher} that replaced
 * the old simple NIO-based {@code core.fs.GlobMatcher}. Mirrors the
 * Python wcmatch {@code BRACE | GLOBSTAR} flag semantics.
 */
class GlobMatcherTest {

    // ----- Star / double-star basics -----

    @Test
    void bareStar_matchesBasenameAtAnyDepth() {
        // wcmatch default: a bare pattern (no /) matches the basename at
        // any depth — that's why "a/b/Foo.java" still matches "*.java".
        GlobMatcher m = GlobMatcher.compile("*.java");
        assertTrue(m.matches("Foo.java"));
        assertTrue(m.matches("a/b/Foo.java"));
        assertFalse(m.matches("Foo.kt"));
    }

    @Test
    void doubleStar_crossesSlashesInRelativePattern() {
        GlobMatcher m = GlobMatcher.compile("**/*.java");
        assertTrue(m.matches("a/b/c/Foo.java"));
        assertFalse(m.matches("src/a/b/c.txt"));
        assertFalse(m.matches("a/b/c/Foo.kt"));
        assertTrue(m.matches("Foo.java"));
    }

    @Test
    void question_matchesSingleChar() {
        GlobMatcher m = GlobMatcher.compile("?.java");
        assertTrue(m.matches("a.java"));
        assertFalse(m.matches("ab.java"));
    }

    @Test
    void characterClass() {
        GlobMatcher m = GlobMatcher.compile("[abc].java");
        assertTrue(m.matches("a.java"));
        assertTrue(m.matches("b.java"));
        assertFalse(m.matches("d.java"));
    }

    @Test
    void characterClassRange() {
        GlobMatcher m = GlobMatcher.compile("[a-z].java");
        assertTrue(m.matches("m.java"));
        assertFalse(m.matches("A.java"));
    }

    @Test
    void characterClassNegation() {
        GlobMatcher m = GlobMatcher.compile("[!abc].java");
        assertTrue(m.matches("d.java"));
        assertFalse(m.matches("a.java"));
    }

    @Test
    void literalRegexMetaIsEscaped() {
        GlobMatcher m = GlobMatcher.compile("foo+bar.txt");
        assertTrue(m.matches("foo+bar.txt"));
        assertFalse(m.matches("fooXbar.txt"));
    }

    // ----- Brace expansion -----

    @Test
    void braceExpansion_alternatives() {
        GlobMatcher m = GlobMatcher.compile("*.{java,kt}");
        assertTrue(m.matches("Foo.java"));
        assertTrue(m.matches("Foo.kt"));
        assertFalse(m.matches("Foo.py"));
    }

    @Test
    void braceExpansion_nested() {
        GlobMatcher m = GlobMatcher.compile("{a,b}/{c,d}");
        assertTrue(m.matches("a/c"));
        assertTrue(m.matches("b/d"));
        assertFalse(m.matches("a/x"));
    }

    @Test
    void braceExpansion_singleAlternation() {
        GlobMatcher m = GlobMatcher.compile("{a,b,c}");
        assertTrue(m.matches("a"));
        assertTrue(m.matches("b"));
        assertTrue(m.matches("c"));
    }

    // ----- wcmatch dotfile rules -----

    @Test
    void barePattern_excludesDotfileBasename() {
        GlobMatcher m = GlobMatcher.compile("*.java");
        assertFalse(m.matches(".hidden.java"));
        assertTrue(m.matches("Foo.java"));
    }

    @Test
    void explicitDotInPattern_canMatchDotfile() {
        GlobMatcher m = GlobMatcher.compile(".*");
        assertTrue(m.matches(".hidden"));
        assertTrue(m.matches(".gitignore"));
    }

    @Test
    void pathRelative_doubleStarDoesNotConsumeLeadingDotSegments() {
        // wcmatch default: a path-relative ** in the middle does NOT
        // consume segments starting with '.', so ".git/Foo" is not
        // matched by "**/Foo".
        GlobMatcher m = GlobMatcher.compile("**/Foo");
        assertTrue(m.matches("a/b/Foo"));
        assertFalse(m.matches(".git/Foo"));
    }

    // ----- Anchored / path-relative -----

    @Test
    void leadingSlashStripped_butStillAnchored() {
        GlobMatcher m = GlobMatcher.compile("/*.py");
        // Pattern with slash: path-relative. Stripped "/" before matching
        // so the literal first segment "*.py" must match.
        assertTrue(m.matches("main.py"));
        assertFalse(m.matches("a/main.py"));
    }

    @Test
    void noSlashPattern_matchesBasenameAtAnyDepth() {
        GlobMatcher m = GlobMatcher.compile("README.md");
        assertTrue(m.matches("README.md"));
        assertTrue(m.matches("docs/README.md"));
        assertFalse(m.matches("README.txt"));
    }

    // ----- Globstar end-position rules -----

    @Test
    void doubleStar_atEnd_mustConsumeAtLeastOneSegment() {
        GlobMatcher m = GlobMatcher.compile("src/**");
        assertTrue(m.matches("src/a"));
        assertTrue(m.matches("src/a/b/c.txt"));
        assertFalse(m.matches("src"));
    }

    @Test
    void doubleStar_atStart_canConsumeZero() {
        GlobMatcher m = GlobMatcher.compile("**/README");
        assertTrue(m.matches("README"));
        assertTrue(m.matches("docs/README"));
        assertTrue(m.matches("a/b/c/README"));
    }

    // ----- Inspectors -----

    @Test
    void pattern_returnsOriginal() {
        GlobMatcher m = GlobMatcher.compile("**/foo.{kt,java}");
        assertEquals("**/foo.{kt,java}", m.pattern());
    }

    @Test
    void patternHasGlobstar_detects() {
        assertTrue(GlobMatcher.compile("**").patternHasGlobstar());
        assertTrue(GlobMatcher.compile("a/**/b").patternHasGlobstar());
        assertFalse(GlobMatcher.compile("a/*/b").patternHasGlobstar());
        assertFalse(GlobMatcher.compile("a.txt").patternHasGlobstar());
    }

    // ----- Error cases -----

    @Test
    void nullPattern_throws() {
        assertThrows(IllegalArgumentException.class, () -> GlobMatcher.compile(null));
    }

    @Test
    void unterminatedCharClass_throws() {
        assertThrows(IllegalArgumentException.class, () -> GlobMatcher.compile("[abc"));
    }

    @Test
    void braceExplosion_throwsAtMaxExpansions() {
        // Build a brace with 1001 alternatives — straight line over MAX_EXPANSIONS=1000.
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 1001; i++) {
            if (i > 0) sb.append(',');
            sb.append("a").append(i);
        }
        sb.append("}");
        final String huge = sb.toString();
        assertThrows(IllegalArgumentException.class, () -> GlobMatcher.compile(huge));
    }

    @Test
    void braceExpansion_atMaxExpansions_doesNotThrow() {
        // 1000 alternatives is right at the cap, must not throw.
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) sb.append(',');
            sb.append("a").append(i);
        }
        sb.append("}");
        final String big = sb.toString();
        // 1000 is allowed; the check is "> MAX_EXPANSIONS".
        GlobMatcher.compile(big);
    }

    @Test
    void maxExpansions_constantIsExposed() {
        assertEquals(1000, GlobMatcher.MAX_EXPANSIONS);
    }

    // ----- Compile is reusable -----

    @Test
    void compileIsReusable_acrossManyInputs() {
        GlobMatcher m = GlobMatcher.compile("src/**/*.java");
        assertNotNull(m);
        assertTrue(m.matches("src/Foo.java"));
        assertTrue(m.matches("src/com/example/Bar.java"));
        assertFalse(m.matches("src/Foo.kt"));
        assertFalse(m.matches("test/Foo.java"));
    }
}
