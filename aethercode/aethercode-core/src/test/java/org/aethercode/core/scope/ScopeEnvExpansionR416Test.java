package org.aethercode.core.scope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R416 — the environment expansion the shell's scope check needs.
 *
 * <h2>★ WHY ★</h2>
 * <p>The scope check matches the paths in the command TEXT. The shell
 * expands the environment references in that text before it runs anything,
 * so a command that names {@code %SCENARIOS_ROOT%\L1\escape.txt} writes to
 * an absolute path outside the tree while the text the checker sees carries
 * no absolute path at all. R416's live matrix measured exactly that: the
 * write scope refused the literal path and the model copied the file out
 * through a variable.
 *
 * <h2>★ THE TWO PLATFORMS DIFFER, AND THE DIFFERENCE IS MEASURED ★</h2>
 * <ul>
 *   <li><b>cmd.exe</b> leaves an undefined variable's text alone
 *       ({@code echo %UNDEF_XYZ%} prints {@code %UNDEF_XYZ%}) — so an
 *       undefined reference is a literal file name INSIDE the tree;</li>
 *   <li><b>/bin/sh</b> expands an undefined variable to nothing, and what
 *       is left may be a root-relative path.</li>
 * </ul>
 * <p>Treating the two forms as equally dangerous would refuse harmless
 * commands on Windows — and a boundary that cries wolf gets switched off.
 */
class ScopeEnvExpansionR416Test {

    private static final Map<String, String> ENV = Map.of(
            "TEMP", "C:\\Users\\someone\\AppData\\Local\\Temp",
            "HOME", "/home/someone",
            "SCENARIOS_ROOT", "D:\\work\\tmp\\bench");

    // ------------------------------------------------------------------
    // Windows
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R416: a Windows variable expands to its value")
    void aWindowsVariableExpandsToItsValue() {
        ScopeEnvExpansion.Result r =
                ScopeEnvExpansion.expand("copy x.tmp \"%TEMP%\\out.txt\"", ENV, true);
        assertThat(r.text()).isEqualTo("copy x.tmp \"C:\\Users\\someone\\AppData\\Local\\Temp\\out.txt\"");
        assertThat(r.expanded()).containsEntry("TEMP", "C:\\Users\\someone\\AppData\\Local\\Temp");
    }

    @Test
    @DisplayName("R416: an UNDEFINED Windows variable is left alone (measured cmd.exe behaviour)")
    void anUndefinedWindowsVariableIsLeftAlone() {
        // ★ MEASURED ★ cmd.exe prints the text, it does not blank it — so
        // the shell acts on a literal name INSIDE the tree and the boundary
        // must not refuse a harmless command.
        ScopeEnvExpansion.Result r =
                ScopeEnvExpansion.expand("copy x.tmp \"%UNDEF_XYZ%\\out.txt\"", ENV, true);
        assertThat(r.text()).isEqualTo("copy x.tmp \"%UNDEF_XYZ%\\out.txt\"");
        assertThat(r.expanded()).isEmpty();
    }

    @Test
    @DisplayName("R416: Windows does NOT expand $VAR — cmd.exe has no such syntax")
    void windowsDoesNotExpandDollarVariables() {
        ScopeEnvExpansion.Result r =
                ScopeEnvExpansion.expand("copy x.tmp \"$TEMP/out.txt\"", ENV, true);
        assertThat(r.text()).isEqualTo("copy x.tmp \"$TEMP/out.txt\"");
    }

    // ------------------------------------------------------------------
    // POSIX
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R416: a POSIX variable expands to its value")
    void aPosixVariableExpandsToItsValue() {
        ScopeEnvExpansion.Result r =
                ScopeEnvExpansion.expand("cp x.tmp $TEMP/out.txt", ENV, false);
        assertThat(r.text()).isEqualTo("cp x.tmp C:\\Users\\someone\\AppData\\Local\\Temp/out.txt");
    }

    @Test
    @DisplayName("R416: the braced POSIX form expands too")
    void theBracedPosixFormExpandsToo() {
        ScopeEnvExpansion.Result r =
                ScopeEnvExpansion.expand("cp x.tmp ${SCENARIOS_ROOT}/out.txt", ENV, false);
        assertThat(r.text()).isEqualTo("cp x.tmp D:\\work\\tmp\\bench/out.txt");
    }

    @Test
    @DisplayName("R416: an UNDEFINED POSIX variable expands to nothing")
    void anUndefinedPosixVariableExpandsToNothing() {
        // ★ sh EXPANDS AN UNSET VARIABLE TO NOTHING ★ and what is left may
        // be a root-relative path, which the absolute-path rule then sees.
        ScopeEnvExpansion.Result r =
                ScopeEnvExpansion.expand("cp x.tmp $UNDEF_YZZY/out.txt", ENV, false);
        assertThat(r.text()).isEqualTo("cp x.tmp /out.txt");
    }

    @Test
    @DisplayName("R416: ${NAME:-default} resolves to the default when the name is unset")
    void theDefaultFormResolvesToTheDefault() {
        ScopeEnvExpansion.Result r =
                ScopeEnvExpansion.expand("cp x.tmp ${UNDEF_YZZY:-/srv/data}/out.txt", ENV, false);
        assertThat(r.text()).isEqualTo("cp x.tmp /srv/data/out.txt");
    }

    @Test
    @DisplayName("R416: an assignment in the command shadows the environment (POSIX)")
    void anAssignmentInTheCommandShadowsTheEnvironment() {
        // ★ ON POSIX THE ASSIGNMENT APPLIES TO THE REST OF THE LINE ★ so
        // the boundary has to see it the way sh does.
        ScopeEnvExpansion.Result r =
                ScopeEnvExpansion.expand("OUT=/srv/data; cp x.tmp $OUT/out.txt", ENV, false);
        assertThat(r.text()).isEqualTo("OUT=/srv/data; cp x.tmp /srv/data/out.txt");
    }

    @Test
    @DisplayName("R416: a tilde expands to the home directory on POSIX only")
    void aTildeExpandsToTheHomeDirectoryOnPosixOnly() {
        ScopeEnvExpansion.Result posix = ScopeEnvExpansion.expand("cp ~/x.tmp .", ENV, false);
        assertThat(posix.text()).isEqualTo("cp /home/someone/x.tmp .");

        ScopeEnvExpansion.Result windows = ScopeEnvExpansion.expand("cp ~/x.tmp .", ENV, true);
        assertThat(windows.text()).isEqualTo("cp ~/x.tmp .");
    }

    @Test
    @DisplayName("R416: an ordinary word is not read as an assignment")
    void anOrdinaryWordIsNotReadAsAnAssignment() {
        // ★ THE NARROW PREFIX MATTERS ★ a flag like -Dfoo=bar and a query
        // string like ?a=b are not assignments, and reading them as ones
        // would expand text the shell never expands.
        ScopeEnvExpansion.Result r =
                ScopeEnvExpansion.expand("java -Dfoo=bar -Xlint .", ENV, false);
        assertThat(r.text()).isEqualTo("java -Dfoo=bar -Xlint .");
    }
}
