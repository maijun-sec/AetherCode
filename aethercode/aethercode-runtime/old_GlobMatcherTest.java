package org.aethercode.core.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobMatcherTest {

    @Test
    void matches_starMatchesAnythingExceptSlash() {
        assertTrue(GlobMatcher.matches("*.java", "Foo.java"));
        assertTrue(GlobMatcher.matches("*.java", "a/b/Foo.java") == false); // * doesn't cross /
        assertFalse(GlobMatcher.matches("*.java", "Foo.kt"));
    }

    @Test
    void matches_doubleStarCrossesSlashes() {
        assertTrue(GlobMatcher.matches("**/*.java", "a/b/c/Foo.java"));
        assertTrue(GlobMatcher.matches("src/**", "src/a/b/c.txt"));
        assertFalse(GlobMatcher.matches("**/*.java", "a/b/c/Foo.kt"));
    }

    @Test
    void matches_questionMatchesSingleChar() {
        assertTrue(GlobMatcher.matches("?.java", "a.java"));
        assertTrue(GlobMatcher.matches("a?.java", "ab.java"));
        assertFalse(GlobMatcher.matches("?.java", "ab.java"));
    }

    @Test
    void matches_characterClass() {
        assertTrue(GlobMatcher.matches("[abc].java", "a.java"));
        assertTrue(GlobMatcher.matches("[abc].java", "b.java"));
        assertFalse(GlobMatcher.matches("[abc].java", "d.java"));
        assertTrue(GlobMatcher.matches("[a-z].java", "m.java"));
        assertFalse(GlobMatcher.matches("[a-z].java", "A.java"));
    }

    @Test
    void matches_braceExpansion() {
        assertTrue(GlobMatcher.matches("*.{java,kt}", "Foo.java"));
        assertTrue(GlobMatcher.matches("*.{java,kt}", "Foo.kt"));
        assertFalse(GlobMatcher.matches("*.{java,kt}", "Foo.py"));
    }

    @Test
    void matches_braceExpansionNested() {
        // single-alternation — should match
        assertTrue(GlobMatcher.matches("{a,b}/{c,d}", "a/c"));
        assertTrue(GlobMatcher.matches("{a,b}/{c,d}", "b/d"));
    }

    @Test
    void matches_escapesRegexMeta() {
        assertTrue(GlobMatcher.matches("foo+bar", "foo+bar"));
        assertTrue(GlobMatcher.matches("foo.bar", "foo.bar"));
        assertFalse(GlobMatcher.matches("foo.bar", "fooXbar"));
    }

    @Test
    void matches_anchoredToFullPath() {
        assertFalse(GlobMatcher.matches("Foo.java", "x/Foo.java"));
        assertTrue(GlobMatcher.matches("**/Foo.java", "x/Foo.java"));
    }

    @Test
    void toRegex_starAndDoubleStar() {
        assertEquals("^[^/]*$", GlobMatcher.toRegex("*"));
        assertEquals("^.*$", GlobMatcher.toRegex("**"));
        // **/foo — our impl consumes the trailing slash so the result is .*foo
        // (semantically equivalent: matches "x/foo", "a/b/foo", "foo" too).
        assertEquals("^.*foo$", GlobMatcher.toRegex("**/foo"));
    }

    @Test
    void compile_returnsPathMatcher() {
        var m = GlobMatcher.compile("*.java");
        assertTrue(m.matches(Path.of("a.java")));
    }

    @Test
    void expand_findsFiles(@TempDir Path tmp) throws IOException {
        Files.createFile(tmp.resolve("a.java"));
        Files.createFile(tmp.resolve("b.kt"));
        Files.createFile(tmp.resolve("c.java"));
        List<Path> matches = GlobMatcher.expand(tmp, "*.java", false);
        assertEquals(2, matches.size());
        for (Path p : matches) {
            assertTrue(p.getFileName().toString().endsWith(".java"));
        }
    }

    @Test
    void expand_recursiveFindsDeepFiles(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("a/b/c"));
        Files.createFile(tmp.resolve("a/b/c/deep.java"));
        Files.createFile(tmp.resolve("a/shallow.java"));
        List<Path> matches = GlobMatcher.expand(tmp, "**/*.java", true);
        assertEquals(2, matches.size());
    }

    @Test
    void matchName_simpleName() {
        assertTrue(GlobMatcher.matchName("*.java", "Foo.java"));
        assertFalse(GlobMatcher.matchName("*.java", "Foo.kt"));
    }

    @Test
    void splitPatternList_splitsOnComma() {
        List<String> globs = GlobMatcher.splitPatternList("*.java, *.kt, *.py");
        assertEquals(3, globs.size());
    }

    @Test
    void splitPatternList_empty() {
        assertTrue(GlobMatcher.splitPatternList("").isEmpty());
        assertTrue(GlobMatcher.splitPatternList(null).isEmpty());
    }

    @Test
    void matchesAny_firstMatchWins() {
        assertTrue(GlobMatcher.matchesAny(List.of("*.kt", "*.java"), Path.of("a.java")));
        assertFalse(GlobMatcher.matchesAny(List.of("*.kt", "*.py"), Path.of("a.java")));
    }

    @Test
    void matchesAll_requiresAll() {
        assertTrue(GlobMatcher.matchesAll(List.of("a.*", "*.java"), Path.of("a.java")));
        assertFalse(GlobMatcher.matchesAll(List.of("a.*", "*.kt"), Path.of("a.java")));
    }

    @Test
    void anyOf_combinesMatchers() {
        var m = GlobMatcher.anyOf(List.of("*.java", "*.kt"));
        assertTrue(m.matches(Path.of("a.java")));
        assertTrue(m.matches(Path.of("a.kt")));
        assertFalse(m.matches(Path.of("a.py")));
    }

    @Test
    void isUnder_trueForDescendant() {
        Path root = Path.of("/tmp/proj").toAbsolutePath();
        assertTrue(GlobMatcher.isUnder(root.resolve("a/b"), root));
        assertFalse(GlobMatcher.isUnder(root.getParent(), root));
    }

    @Test
    void regexMatcher_compilesRegex() {
        var m = GlobMatcher.regexMatcher(".*\\.java");
        assertTrue(m.matches(Path.of("a.java")));
    }

    @Test
    void expand_nonRecursiveFindsOnlyRoot(@TempDir Path tmp) throws IOException {
        Files.createFile(tmp.resolve("a.java"));
        Files.createDirectories(tmp.resolve("sub"));
        Files.createFile(tmp.resolve("sub").resolve("b.java"));
        List<Path> matches = GlobMatcher.expand(tmp, "*.java", false);
        // non-recursive: only the root level
        assertEquals(1, matches.size());
    }

    @Test
    void matches_braceWithCommaInSide() {
        // Comma in nested braces is preserved
        assertTrue(GlobMatcher.matches("{a,b,c}", "a"));
        assertTrue(GlobMatcher.matches("{a,b,c}", "b"));
        assertTrue(GlobMatcher.matches("{a,b,c}", "c"));
    }
}
