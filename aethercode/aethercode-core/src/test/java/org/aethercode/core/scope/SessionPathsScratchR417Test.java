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

package org.aethercode.core.scope;

import org.aethercode.core.app.AppState;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R417: the session's SCRATCH AREA — the engine-owned place where a turn
 * may put the things that are NOT the user's project.
 *
 * <h2>★ WHY THIS EXISTS ★</h2>
 * <p>R416's live matrix measured it: a turn asked to verify its own work
 * left nine files in the user's tree — a {@code .verify/} scaffolding
 * directory, a verification script, a transcript of the run. Every one of
 * them was scaffolding the model built for itself, and every one of them
 * is garbage the user has to delete by hand.
 *
 * <p>★ AND THE BOUNDARY COULD NOT FIX IT ★ those files were INSIDE the
 * tree, so every write of them was LEGAL. The failure was not an escape;
 * it was the model choosing the wrong place. ⇒ the fix is not a tighter
 * boundary but a place that is BOTH outside the tree AND reachable, and
 * a prompt that says so.
 *
 * <h2>★ WHY THE ENGINE HOME IS A PARAMETER ★</h2>
 * <p>The engine home comes from the ENVIRONMENT, and a test cannot set an
 * environment variable. A test that used the ambient home would measure
 * whatever this machine happens to say — and the assertion would be about
 * the machine, not about the code.
 */
class SessionPathsScratchR417Test {

    @Test
    @DisplayName("R417: the scratch area lives under the engine's own home, in a 'scratch' directory")
    void theScratchAreaLivesUnderTheEngineHome(@TempDir Path home) {
        Path scratch = SessionPaths.scratchRoot("s1", home);

        assertThat(scratch).isNotNull();
        assertThat(scratch.startsWith(home.toAbsolutePath().normalize())).isTrue();
        assertThat(scratch.toString().replace('\\', '/')).contains("/scratch/");
        assertThat(scratch.getFileName().toString()).isEqualTo("s1");
    }

    @Test
    @DisplayName("R417: two sessions get two scratch areas")
    void twoSessionsGetTwoScratchAreas(@TempDir Path home) {
        Path a = SessionPaths.scratchRoot("s1", home);
        Path b = SessionPaths.scratchRoot("s2", home);
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("R417: a session id cannot name a directory outside the scratch root")
    void aSessionIdCannotEscapeTheScratchRoot(@TempDir Path home) {
        // ★ THE ID IS NOT TRUSTED ★ a session id can arrive from the wire.
        // If it could carry a separator, it could name a directory
        // OUTSIDE the scratch root — which is exactly the boundary this
        // class exists to hold.
        Path scratch = SessionPaths.scratchRoot("../../escape", home);
        assertThat(scratch).isNotNull();
        Path scratchRoot = home.resolve("scratch").toAbsolutePath().normalize();
        assertThat(SessionPaths.isInside(scratch, scratchRoot))
                .as("the scratch area must stay inside the scratch root")
                .isTrue();
    }

    @Test
    @DisplayName("R417: '..' and '.' are not directory names")
    void dotDotIsNotADirectoryName() {
        assertThat(SessionPaths.safeDirName("..")).isNotEqualTo("..");
        assertThat(SessionPaths.safeDirName(".")).isNotEqualTo(".");
        assertThat(SessionPaths.safeDirName("")).isNotBlank();
        assertThat(SessionPaths.safeDirName(null)).isNotBlank();
    }

    @Test
    @DisplayName("R417: the call context resolves the scratch area of ITS session")
    void theCallContextResolvesTheScratchArea() {
        Tool.CallContext ctx = Tool.CallContext.of("s1");
        ctx.setExtra("app_state", new AppState("s1", Path.of("")));

        Path fromCtx = SessionPaths.scratchRoot(ctx);
        Path fromId = SessionPaths.scratchRoot("s1");

        assertThat(fromCtx).isNotNull();
        // ★ ONE QUESTION, ONE ANSWER ★ the context path and the session-id
        // path must agree — two answers to one question drift.
        assertThat(fromCtx).isEqualTo(fromId);
        assertThat(fromCtx.toString().replace('\\', '/')).endsWith("/scratch/s1");
    }

    @Test
    @DisplayName("R417: no session means no scratch area")
    void noSessionMeansNoScratchArea(@TempDir Path home) {
        assertThat(SessionPaths.scratchRoot(null, home)).isNull();
        assertThat(SessionPaths.scratchRoot("", home)).isNull();
        assertThat(SessionPaths.scratchRoot("   ", home)).isNull();
    }

    @Test
    @DisplayName("R417: no engine home means no scratch area")
    void noEngineHomeMeansNoScratchArea() {
        assertThat(SessionPaths.scratchRoot("s1", null)).isNull();
    }
}
