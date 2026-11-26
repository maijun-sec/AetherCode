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

package org.aethercode.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R416 — the CLI must not ADVERTISE a cap it does not apply.
 *
 * <h2>★ WHAT WAS WRONG ★</h2>
 * <p>{@code --max-turns} said <b>"Cap on model turns per query (default
 * 50)"</b> — and the engine never read the value. R83 removed the cap
 * and left the knob (and the promise) behind. A user who read the help
 * and set {@code --max-turns 10} got an unbounded turn, and a user who
 * relied on the default 50 got one too.
 *
 * <h2>★ THE CONTRACT NOW ★</h2>
 * <ul>
 *   <li>the default is UNBOUNDED, and the help says so;</li>
 *   <li>when a cap is set it is ENFORCED (the engine-side half is
 *       pinned by {@code QueryEngineTurnBudgetR416Test});</li>
 *   <li>{@code AETHERCODE_MAX_TURNS} sets it without a rebuild — the
 *       desktop spawns the daemon with a fixed argument list, so the
 *       environment is the only channel a user has.</li>
 * </ul>
 */
class MainMaxTurnsR416Test {

    // ------------------------------------------------------------------
    // the resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R416: no flag and no env var means unbounded")
    void nothingSetMeansUnbounded() {
        assertEquals(0, Main.resolveMaxTurns(0, null));
        assertEquals(0, Main.resolveMaxTurns(0, "  "));
    }

    @Test
    @DisplayName("R416: the environment variable sets the cap")
    void theEnvVarSetsTheCap() {
        assertEquals(12, Main.resolveMaxTurns(0, "12"));
        assertEquals(12, Main.resolveMaxTurns(0, " 12 "));
    }

    @Test
    @DisplayName("R416: the flag wins over the environment")
    void theFlagWins() {
        assertEquals(5, Main.resolveMaxTurns(5, "12"));
    }

    @Test
    @DisplayName("R416: a malformed environment value is ignored, not fatal")
    void aMalformedEnvValueIsIgnored() {
        assertEquals(0, Main.resolveMaxTurns(0, "not-a-number"));
        assertEquals(0, Main.resolveMaxTurns(0, "-5"));
    }

    // ------------------------------------------------------------------
    // the help text — the promise the user reads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R416: --help must not advertise a default the engine does not apply")
    void theHelpTextIsTrue() throws Exception {
        String src = Files.readString(sourcePath(), StandardCharsets.UTF_8);
        int i = src.indexOf("--max-turns");
        assertTrue(i > 0, "the --max-turns option must exist");
        String option = src.substring(i, Math.min(src.length(), i + 1200));

        assertFalse(option.contains("default 50"),
                "the help must not claim a default of 50 — the engine's default is unbounded");
        assertTrue(option.contains("UNBOUNDED") || option.contains("unbounded"),
                "the help must say the default is unbounded");
        assertTrue(option.contains("AETHERCODE_MAX_TURNS"),
                "the help must name the environment variable that sets the cap");
        assertTrue(option.contains("max_iterations"),
                "the help must name the stop reason the user will see when the cap is hit");
    }

    @Test
    @DisplayName("R416: the option's default value is 0 (unbounded), not 50")
    void theOptionDefaultIsZero() throws Exception {
        String src = Files.readString(sourcePath(), StandardCharsets.UTF_8);
        int i = src.indexOf("int maxTurns = ");
        assertTrue(i > 0, "the maxTurns field must exist");
        String line = src.substring(i, src.indexOf(';', i));
        assertTrue(line.contains("= 0"),
                "the CLI default must be 0 (unbounded) — the old 50 was never enforced: " + line);
    }

    private static Path sourcePath() {
        return Paths.get("src", "main", "java", "org", "aethercode", "cli", "Main.java");
    }
}
