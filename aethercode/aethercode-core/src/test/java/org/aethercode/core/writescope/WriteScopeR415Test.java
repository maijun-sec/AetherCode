/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.writescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R415: the WRITE SCOPE — the hard boundary on where a turn may write.
 *
 * <p>★ WHAT EACH CASE PINS ★ every case names the rule it pins, because a
 * boundary that cannot be told apart from its neighbours is a boundary
 * nobody can rely on.
 */
class WriteScopeR415Test {

    /**
     * A scope with the boundary ENFORCED.
     *
     * <p>★ THE ESCAPE HATCH IS PINNED OFF ★ this machine sets
     * {@code AETHERCODE_ALLOW_ANY_PATH=1} at the USER level, so a scope
     * that read the variable would measure the ESCAPED behaviour — and
     * every "the boundary holds" case would pass while the boundary was
     * doing nothing at all. That is not a test; that is a test that
     * congratulates itself.
     */
    private static WriteScope scope(Path root, String request) {
        return new WriteScope(root, request, false);
    }

    // ------------------------------------------------------------------
    // W1 — the tree is the boundary
    // ------------------------------------------------------------------

    @Test
    void aPathInsideTheTreeIsAllowed(@TempDir Path root) {
        WriteScope.Decision d = scope(root, "").check(root.resolve("src/AppConfig.java"));
        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isNull();
    }

    @Test
    void aPathOutsideTheTreeIsRefused(@TempDir Path root) {
        WriteScope.Decision d = scope(root, "").check(root.getParent().resolve("elsewhere/File.java"));
        assertThat(d.allowed()).isFalse();
        assertThat(d.message()).contains("outside the working directory");
    }

    @Test
    void aSiblingWithTheSamePrefixIsOutsideTheTree(@TempDir Path root) {
        // ★ THE ONE-LETTER ESCAPE ★ `root` is ".../abc"; ".../abcde" is a
        // DIFFERENT directory. A plain string prefix would call it inside.
        Path sibling = root.resolveSibling(root.getFileName() + "de");
        WriteScope.Decision d = scope(root, "").check(sibling.resolve("File.java"));
        assertThat(d.allowed()).isFalse();
        assertThat(d.message()).contains("outside the working directory");
    }

    // ------------------------------------------------------------------
    // W2 — tool-owned directories
    // ------------------------------------------------------------------

    @Test
    void aWriteIntoTheGitDirectoryIsRefused(@TempDir Path root) {
        WriteScope.Decision d = scope(root, "").check(root.resolve(".git/config"));
        assertThat(d.allowed()).isFalse();
        assertThat(d.message()).contains(".git");
    }

    @Test
    void aWriteIntoNodeModulesIsRefused(@TempDir Path root) {
        WriteScope.Decision d = scope(root, "").check(root.resolve("node_modules/left-pad/index.js"));
        assertThat(d.allowed()).isFalse();
        assertThat(d.message()).contains("node_modules");
    }

    @Test
    void aWriteIntoATargetDirectoryIsRefused(@TempDir Path root) {
        // ★ BUILD OUTPUT IS NOT THE MODEL'S ★ a class file or a build
        // artefact under target/ belongs to the build, not to the turn.
        WriteScope.Decision d = scope(root, "").check(root.resolve("target/classes/App.class"));
        assertThat(d.allowed()).isFalse();
        assertThat(d.message()).contains("target");
    }

    @Test
    void aWriteIntoAPythonCacheIsRefused(@TempDir Path root) {
        WriteScope.Decision d = scope(root, "").check(root.resolve("__pycache__/mod.cpython-312.pyc"));
        assertThat(d.allowed()).isFalse();
        assertThat(d.message()).contains("__pycache__");
    }

    // ------------------------------------------------------------------
    // W3 — do not invent a build system
    // ------------------------------------------------------------------

    @Test
    void inventingAPomInAProjectWithNoBuildIsRefused(@TempDir Path root) {
        // ★ THE MEASURED FAILURE ★ R414's live matrix: asked for ONE unit
        // test, the turn scaffolded a whole Maven project.
        WriteScope.Decision d = scope(root, "给 LruCache 写一个单元测试").check(root.resolve("pom.xml"));
        assertThat(d.allowed()).isFalse();
        assertThat(d.message()).contains("no build system");
    }

    @Test
    void inventingAPomWhenTheRequestAsksForMavenIsAllowed(@TempDir Path root) {
        // ★ THE ESCAPE ★ the user asked for a Maven build; refusing it
        // would block work the user asked for.
        WriteScope.Decision d = scope(root, "给这个项目加一个 maven 构建").check(root.resolve("pom.xml"));
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void addingAPomToAProjectThatHasABuildIsAllowed(@TempDir Path root) throws IOException {
        // ★ A PROJECT THAT HAS A BUILD ★ the failure being prevented is a
        // project that has NO build suddenly acquiring one.
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n");
        WriteScope.Decision d = scope(root, "写一个单元测试").check(root.resolve("pom.xml"));
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void aNestedBuildFileIsAllowed(@TempDir Path root) throws IOException {
        // ★ A MULTI-MODULE PROJECT ★ module/pom.xml under a root that has
        // one is normal; the rule is about the ROOT.
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        WriteScope.Decision d = scope(root, "写一个单元测试").check(root.resolve("module/pom.xml"));
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void aGitignoreInAProjectWithNoBuildIsRefused(@TempDir Path root) {
        WriteScope.Decision d = scope(root, "写一个单元测试").check(root.resolve(".gitignore"));
        assertThat(d.allowed()).isFalse();
    }

    @Test
    void anOrdinarySourceFileIsNeverScaffolding(@TempDir Path root) {
        // ★ THE SCOPE DOES NOT OVER-REACH ★ a test file in a project with
        // no build is the normal case, not a scaffold.
        WriteScope.Decision d = scope(root, "给 LruCache 写一个单元测试")
                .check(root.resolve("src/test/java/demo/LruCacheTest.java"));
        assertThat(d.allowed()).isTrue();
    }

    // ------------------------------------------------------------------
    // the escape hatch
    // ------------------------------------------------------------------

    @Test
    void theEscapeHatchSwitchesTheBoundaryOff(@TempDir Path root) {
        // ★ THE USER'S OPT-OUT ★ when it is set, the boundary does not
        // apply. It has to be PINNED, because this machine has it set at
        // the USER level — and a boundary that is silently inert is worse
        // than no boundary, because it looks like it is working.
        WriteScope scope = new WriteScope(root, "写测试", true);
        WriteScope.Decision d = scope.check(root.getParent().resolve("elsewhere/File.java"));
        assertThat(d.allowed()).isTrue();
        assertThat(scope.isEscapeActive()).isTrue();
    }

    @Test
    void theScopeSaysWhetherTheEscapeIsActive(@TempDir Path root) {
        assertThat(scope(root, "").isEscapeActive()).isFalse();
    }

    @Test
    void anEscapedWriteSaysThatTheBoundaryWasOff(@TempDir Path root) {
        // ★ THE BOUNDARY MUST NOT GO QUIET ★ this machine sets the
        // escape at the USER level, so the scope is inert by default
        // here — and a write that breaks the rules would then look
        // exactly like a write that obeys them. The user is reading
        // "wrote N bytes" and has no other way to know.
        WriteScope escaped = new WriteScope(root, "写测试", true);
        WriteScope.Decision d = escaped.check(root.getParent().resolve("elsewhere/File.java"));

        assertThat(d.allowed()).isTrue();
        assertThat(d.escapeNotice())
                .contains("write scope is disabled")
                .contains("AETHERCODE_ALLOW_ANY_PATH")
                .contains("outside the working directory");
    }

    @Test
    void aWriteThatBreaksNoRuleCarriesNoEscapeNotice(@TempDir Path root) {
        // ★ NO NOISE ★ an ordinary write must not carry a notice; the
        // notice is for the writes that broke a rule.
        WriteScope escaped = new WriteScope(root, "写测试", true);
        WriteScope.Decision d = escaped.check(root.resolve("src/AppConfig.java"));

        assertThat(d.allowed()).isTrue();
        assertThat(d.escapeNotice()).isNull();
        assertThat(d.applyNotice("wrote 10 bytes")).isEqualTo("wrote 10 bytes");
    }

    // ------------------------------------------------------------------
    // the message
    // ------------------------------------------------------------------

    @Test
    void aRefusalSaysWhatToDoNext(@TempDir Path root) {
        // ★ A REFUSAL THE MODEL CAN ACT ON ★ "no" alone is not an
        // instruction.
        String msg = scope(root, "写测试").check(root.resolve("pom.xml")).message();
        assertThat(msg).contains("write refused");
        assertThat(msg).contains("nothing here to run it with");
    }
}
