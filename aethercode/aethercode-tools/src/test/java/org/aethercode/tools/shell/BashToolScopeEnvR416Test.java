package org.aethercode.tools.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R416 — the shell's scope check must see the paths the SHELL will see.
 *
 * <h2>★ THE HOLE THE LIVE MATRIX FOUND ★</h2>
 * <p>The scope check matches the paths in the command text. The shell
 * expands the environment references in that text before it runs anything,
 * so:
 *
 * <pre>
 *   copy "sb\stage.tmp" "%SCENARIOS_ROOT%\L1\escape.txt"
 * </pre>
 *
 * <p>writes to an absolute path OUTSIDE the tree while the text the checker
 * sees carries no absolute path at all. R416's live matrix measured it:
 * the write scope refused the literal path twice, and the model copied the
 * file out through a variable — and the scenario's own check ("NOTHING was
 * written outside the tree") went red.
 *
 * <h2>★ WHY THIS IS A FIX AND NOT A POLICY CHANGE ★</h2>
 * <p>R415's rule already says: "a command that can WRITE must not be
 * pointed outside the tree". The literal form of that path is refused. The
 * variable form is the SAME path in a different spelling — so the defect
 * is that the checker and the shell disagreed about what the command
 * means, not that the policy was ambiguous.
 */
class BashToolScopeEnvR416Test {

    /** The scope check with an explicit environment — the machine's own is not a fixture. */
    private static String scopeViolation(String command, Path cwd, Map<String, String> env) {
        return BashTool.checkScopeEscapeForTest(command, cwd.toFile(), env);
    }

    @Test
    @DisplayName("R416: a write through an environment variable is refused")
    void aWriteThroughAnEnvironmentVariableIsRefused(@TempDir Path root) throws Exception {
        Path outside = root.getParent().resolve("outside-" + root.getFileName());
        java.nio.file.Files.createDirectories(outside);
        try {
            Map<String, String> env = Map.of("SCENARIOS_ROOT", outside.toString());
            String v = scopeViolation(
                    "copy \"stage.tmp\" \"%SCENARIOS_ROOT%\\escape.txt\"", root, env);

            assertThat(v)
                    .as("the write must be refused: the shell expands the variable "
                            + "and writes outside the tree")
                    .isNotNull();
            assertThat(v)
                    .as("the refusal must name the path the shell will really use")
                    .contains(outside.toString());
            assertThat(v)
                    .as("the refusal must name the variable it came from — the "
                            + "diagnosis decides what the model does next")
                    .contains("SCENARIOS_ROOT");
        } finally {
            java.nio.file.Files.deleteIfExists(outside);
        }
    }

    @Test
    @DisplayName("R416: a write through a variable that points INSIDE the tree is allowed")
    void aWriteThroughAVariableInsideTheTreeIsAllowed(@TempDir Path root) {
        Map<String, String> env = Map.of("INSIDE", root.toString());
        String v = scopeViolation("copy \"stage.tmp\" \"%INSIDE%\\ok.txt\"", root, env);
        assertThat(v).isNull();
    }

    @Test
    @DisplayName("R416: an UNDEFINED variable does not make the boundary cry wolf")
    void anUndefinedVariableDoesNotMakeTheBoundaryCryWolf(@TempDir Path root) {
        // ★ MEASURED ★ cmd.exe leaves an undefined variable's text alone, so
        // the shell acts on a literal name INSIDE the tree. Refusing it
        // would refuse a harmless command — and a boundary that cries wolf
        // gets switched off.
        String v = scopeViolation("copy \"stage.tmp\" \"%UNDEF_XYZ%\\ok.txt\"", root, Map.of());
        assertThat(v).isNull();
    }

    @Test
    @DisplayName("R416: a READ-ONLY command may still look wherever it likes")
    void aReadOnlyCommandMayStillLookWhereverItLikes(@TempDir Path root) throws Exception {
        Path outside = root.getParent().resolve("outside-ro-" + root.getFileName());
        java.nio.file.Files.createDirectories(outside);
        try {
            Map<String, String> env = Map.of("OUTSIDE", outside.toString());
            String v = scopeViolation("type \"%OUTSIDE%\\secret.txt\"", root, env);
            assertThat(v)
                    .as("R415's policy: the READ side of the shell is an explicit "
                            + "handover item (it needs an OS-level sandbox) — this "
                            + "round closes the WRITE hole, it does not pretend to "
                            + "close the read one")
                    .isNull();
        } finally {
            java.nio.file.Files.deleteIfExists(outside);
        }
    }

    @Test
    @DisplayName("R416: the literal path and the variable path are the SAME verdict")
    void theLiteralPathAndTheVariablePathAreTheSameVerdict(@TempDir Path root) throws Exception {
        Path outside = root.getParent().resolve("outside-same-" + root.getFileName());
        java.nio.file.Files.createDirectories(outside);
        try {
            String literal = scopeViolation(
                    "copy \"stage.tmp\" \"" + outside.toString() + "\\escape.txt\"", root, Map.of());
            String viaVar = scopeViolation(
                    "copy \"stage.tmp\" \"%SCENARIOS_ROOT%\\escape.txt\"", root,
                    Map.of("SCENARIOS_ROOT", outside.toString()));

            assertThat(literal).isNotNull();
            assertThat(viaVar)
                    .as("one spelling of the same path, one verdict")
                    .isNotNull();
        } finally {
            java.nio.file.Files.deleteIfExists(outside);
        }
    }

    // ------------------------------------------------------------------
    // ★ AND THE PATH THE CHECKER SEES MUST BE THE PATH THE SHELL SEES ★
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R416: a URL is not a drive path")
    void aUrlIsNotADrivePath(@TempDir Path root) {
        // ★ WHAT THE MATRIX MEASURED ★ the boundary read the scheme's last
        // letter and its colon as a DRIVE LETTER and refused the command
        // with the reason "the command names 's://repo1.maven.org', which
        // is outside the working directory" — so the model tried eight
        // ways to download the same jar and was refused eight times, with
        // a reason it could not act on.
        String v = scopeViolation("curl -sI https://repo1.maven.org/maven2/x.jar", root, Map.of());
        assertThat(v)
                .as("a URL is not a path, and a download is not a write outside the tree")
                .isNull();
    }

    @Test
    @DisplayName("R416: a single-letter segment is a flag, not a path")
    void aSingleLetterSegmentIsAFlagNotAPath(@TempDir Path root) {
        // ★ MEASURED ★ `findstr /n "." src\LruCache.java` was refused with
        // the reason "the command names '/n', which is outside the working
        // directory" — and the model reworded the same command.
        String v = scopeViolation("findstr /n \".\" src\\LruCache.java", root, Map.of());
        assertThat(v)
                .as("/n is a switch, not a path")
                .isNull();
    }

    @Test
    @DisplayName("R416: a real POSIX path is still refused")
    void aRealPosixPathIsStillRefused(@TempDir Path root) {
        String v = scopeViolation("cp stage.tmp /etc/evil.txt", root, Map.of());
        assertThat(v)
                .as("the carve-out is for ONE letter, not for paths")
                .isNotNull();
    }
}
