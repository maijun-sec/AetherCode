package org.aethercode.tools.shell;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contract test for the {@link BashTool#isReadOnly(Map)}
 * helper that powers the {@code 智能授权} (smart) tier of
 * the 3-tier permission model.
 *
 * <p>legacy, {@code ACCEPT_EDITS} was an all-or-nothing
 * "auto-allow file edits; ask for bash / network" mode that
 * still prompted for {@code cat} / {@code ls} / {@code pwd}.
 * That defeated the mode: a user who flipped to ACCEPT_EDITS
 * to stop seeing permission prompts for everyday read-only
 * commands was still prompted. R203 introduces a smarter
 * auto-allow that:
 * <ol>
 *   <li>Tokenises the bash command and checks every segment
 *       against a documented read-only command whitelist
 *       (ls, cat, pwd, find, grep, head, tail, wc, stat, df,
 *       du, tree, …).</li>
 *   <li>Refuses ANY shell metachar / destructive token
 *       ({@code >}, {@code |}, {@code &&}, {@code rm},
 *       {@code mv}, {@code chmod}, {@code sudo}, …) — even
 *       when embedded as an argument. The check is
 *       deliberately conservative: when in doubt, we ask.</li>
 * </ol>
 *
 * <p>The tests below pin the four critical corner cases:
 * <ol>
 *   <li>Read-only command alone → true</li>
 *   <li>Read-only command with destructive token → false</li>
 *   <li>Mutating command (rm, mv, chmod) → false</li>
 *   <li>Empty / null / missing command → false (defensive:
 *       the tool itself rejects, but the prompter sees it
 *       first — better to ask than to auto-allow)</li>
 * </ol>
 */
class BashToolR203ReadOnlyTest {

    private static Map<String, Object> input(String command) {
        Map<String, Object> m = new HashMap<>();
        m.put("command", command);
        return m;
    }

    // --- happy path: read-only commands --------------------------------

    @Test
    void readOnly_ls() {
        // The most common read-only command. Should
        // never prompt under 智能授权.
        assertThat(BashTool.isReadOnly(input("ls"))).isTrue();
        assertThat(BashTool.isReadOnly(input("ls -la"))).isTrue();
        assertThat(BashTool.isReadOnly(input("ls -la /tmp"))).isTrue();
    }

    @Test
    void readOnly_cat() {
        // cat a single file is read-only. The tokeniser
        // must not mistake the file argument for a
        // command (so cat is the first token, not e.g.
        // "cat" + "/etc/passwd").
        assertThat(BashTool.isReadOnly(input("cat /etc/hostname"))).isTrue();
        assertThat(BashTool.isReadOnly(input("cat foo.txt bar.txt"))).isTrue();
    }

    @Test
    void readOnly_pipeOfReadOnlyCommands() {
        // `cat foo | head` — both segments are
        // read-only. The tokeniser splits on & | ; so
        // a pipe of read-only commands is itself
        // read-only.
        assertThat(BashTool.isReadOnly(input("cat foo.txt | head -5"))).isTrue();
        assertThat(BashTool.isReadOnly(input("ls -la | grep foo | head"))).isTrue();
        assertThat(BashTool.isReadOnly(input("find . -name '*.java' | wc -l"))).isTrue();
    }

    @Test
    void readOnly_pathPrefixedCommand() {
        // /bin/ls is still ls. The tokeniser strips
        // the path prefix so a fully-qualified command
        // matches the whitelist.
        assertThat(BashTool.isReadOnly(input("/bin/ls -la"))).isTrue();
        assertThat(BashTool.isReadOnly(input("/usr/bin/cat /etc/hostname"))).isTrue();
    }

    @Test
    void readOnly_envVarPrefix() {
        // `LC_ALL=C ls` is still `ls`. The tokeniser
        // strips leading env-var assignments so the
        // first command word is detected correctly.
        assertThat(BashTool.isReadOnly(input("LC_ALL=C ls"))).isTrue();
        assertThat(BashTool.isReadOnly(input("LANG=en_US.UTF-8 grep -r foo ."))).isTrue();
    }

    @Test
    void readOnly_windowsCommands() {
        // Windows shells: dir, type, ver, systeminfo.
        // A user on Windows should be able to flip to
        // 智能授权 and not be asked for `dir` / `type`.
        assertThat(BashTool.isReadOnly(input("dir C:\\tmp"))).isTrue();
        assertThat(BashTool.isReadOnly(input("type foo.txt"))).isTrue();
        assertThat(BashTool.isReadOnly(input("ver"))).isTrue();
    }

    // --- destructive tokens → always false -----------------------------

    @Test
    void destructive_redirect_breaksReadOnly() {
        // `ls > out.txt` is a write — the `>` token
        // makes it mutating. Even though the first
        // command (ls) is read-only, the destructive
        // token in the command body flips the verdict.
        assertThat(BashTool.isReadOnly(input("ls > out.txt"))).isFalse();
        assertThat(BashTool.isReadOnly(input("cat foo > bar.txt"))).isFalse();
        assertThat(BashTool.isReadOnly(input("ls >> out.txt"))).isFalse();
    }

    @Test
    void destructive_pipeToFile() {
        // `cat | tee` is a write. The pipe `|` token
        // is the destructive marker, not the pipe
        // operator semantics.
        assertThat(BashTool.isReadOnly(input("cat foo | tee out.txt"))).isFalse();
    }

    @Test
    void destructive_rmCommand() {
        // `rm` is the canonical mutating command. The
        // substring `rm ` (with trailing space) appears
        // in `rm foo`, `rm -rf foo`, etc. The token
        // check is conservative: `rm` embedded in a
        // longer word (e.g. `firmware`) does NOT
        // match (the substring is ` rm `, not just
        // `rm`). The dedicated R130 denylist on top
        // catches the dangerous `rm -rf` patterns.
        assertThat(BashTool.isReadOnly(input("rm foo.txt"))).isFalse();
        assertThat(BashTool.isReadOnly(input("rm -rf /tmp"))).isFalse();
        assertThat(BashTool.isReadOnly(input("sudo rm -rf /"))).isFalse();
    }

    @Test
    void destructive_mv_chmod_chown() {
        // File-modifying commands. Each has its own
        // token in the destructive list.
        assertThat(BashTool.isReadOnly(input("mv foo bar"))).isFalse();
        assertThat(BashTool.isReadOnly(input("chmod 755 foo"))).isFalse();
        assertThat(BashTool.isReadOnly(input("chown user foo"))).isFalse();
    }

    @Test
    void destructive_chainedAndOr() {
        // `&&` and `||` are destructive because they
        // enable conditional execution. `cat a && cat b`
        // could become `cat a && rm b` in a one-line
        // refactor and the second arm is mutating.
        assertThat(BashTool.isReadOnly(input("cat a && cat b"))).isFalse();
        assertThat(BashTool.isReadOnly(input("ls || echo failed"))).isFalse();
    }

    @Test
    void destructive_subshellOrBackticks() {
        // `$(...)` and backticks enable command
        // substitution. A read-only command followed by
        // a subshell is treated as destructive.
        assertThat(BashTool.isReadOnly(input("echo $(cat foo)"))).isFalse();
        assertThat(BashTool.isReadOnly(input("echo `cat foo`"))).isFalse();
    }

    @Test
    void destructive_unknownCommand() {
        // The first command isn't in the read-only
        // whitelist. We don't try to be clever — we
        // just ask. This covers `node`, `python`,
        // `bash` (the shell itself, which can do
        // anything), `curl`, etc.
        assertThat(BashTool.isReadOnly(input("node script.js"))).isFalse();
        assertThat(BashTool.isReadOnly(input("python -c 'print(1)'"))).isFalse();
        assertThat(BashTool.isReadOnly(input("bash -c 'ls'"))).isFalse();
        assertThat(BashTool.isReadOnly(input("curl https://example.com"))).isFalse();
    }

    @Test
    void destructive_wordContainingRmIsSafe() {
        // The destructive token is ` rm ` (with
        // surrounding spaces), not just `rm`. So
        // `firmware`, `germ`, `arm` etc. are NOT
        // matched — they're words, not commands.
        // This test pins the conservative substring
        // to prevent an over-eager refactor from
        // flagging "firmware" as destructive.
        assertThat(BashTool.isReadOnly(input("echo firmware"))).isTrue();
        assertThat(BashTool.isReadOnly(input("cat firmware.txt"))).isTrue();
    }

    // --- defensive: null / empty / missing input -----------------------

    @Test
    void nullInput_isNotReadOnly() {
        // The prompter sees this before the tool
        // does. Better to ask than to auto-allow a
        // tool call that has no parameters at all.
        assertThat(BashTool.isReadOnly(null)).isFalse();
    }

    @Test
    void emptyInputMap_isNotReadOnly() {
        assertThat(BashTool.isReadOnly(new HashMap<>())).isFalse();
    }

    @Test
    void missingCommand_isNotReadOnly() {
        // No `command` field. The BashTool itself
        // would error out with "command is required"
        // (prior round), but the prompter sees it first.
        assertThat(BashTool.isReadOnly(new HashMap<>())).isFalse();
    }

    @Test
    void blankCommand_isNotReadOnly() {
        // Whitespace-only or empty string. Same
        // reasoning as missingCommand.
        assertThat(BashTool.isReadOnly(input(""))).isFalse();
        assertThat(BashTool.isReadOnly(input("   "))).isFalse();
        assertThat(BashTool.isReadOnly(input("\t\n"))).isFalse();
    }

    @Test
    void nonStringCommand_isNotReadOnly() {
        // A confused model might pass a number or
        // list. Treat anything non-string as not
        // read-only — the tool itself will reject.
        Map<String, Object> m = new HashMap<>();
        m.put("command", 42);
        assertThat(BashTool.isReadOnly(m)).isFalse();
    }

    // --- path preservation: the verdict is preserved across the
    //     pass-through. Pins the afterward behaviour: every
    //     safe-looking command auto-allows, every risky one
    //     asks, and the verdict is independent of arguments
    //     (the tokeniser only inspects the first command
    //     word of each segment). --------------------------------------

    @Test
    void readOnly_commandWithQuotedArgument() {
        // `grep "foo bar" file` — the quoted argument
        // contains a space. The tokeniser must still
        // see the first word as `grep`.
        assertThat(BashTool.isReadOnly(input("grep \"foo bar\" file.txt"))).isTrue();
    }

    @Test
    void readOnly_commandWithGlobArgument() {
        // `cat *.txt` — glob is an argument, not a
        // command. The tokeniser should treat `cat`
        // as the first word.
        assertThat(BashTool.isReadOnly(input("cat *.txt"))).isTrue();
        assertThat(BashTool.isReadOnly(input("ls *.java | wc -l"))).isTrue();
    }

    @Test
    void readOnly_chainedSafeCommands() {
        // `cat a; cat b` — the `;` chain makes it
        // destructive (the token list includes `;`),
        // so this should NOT auto-allow. Even though
        // both arms are read-only, the user might
        // extend the chain to a mutating command.
        assertThat(BashTool.isReadOnly(input("cat a; cat b"))).isFalse();
    }
}
