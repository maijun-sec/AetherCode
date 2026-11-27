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
 * R417 — the CLI's turn TIME budget: the flag, the environment, and the
 * promise the help makes.
 *
 * <h2>★ WHY A SECOND BUDGET ★</h2>
 * <p>A turn COUNT cannot bound a turn whose calls are all different, and
 * R416's live matrix measured exactly that: two scenarios whose work was
 * DONE and whose turn kept running anyway (104 and 142 model calls, every
 * one of them different). The stall detector cannot catch it either — it
 * measures whether a RESULT was already seen, and a rabbit hole keeps
 * producing results it has not seen.
 *
 * <h2>★ "NOT SET" AND "UNBOUNDED" MUST BE DIFFERENT ★</h2>
 * <p>The flag's default is {@code -1} = "the user said nothing", and
 * {@code 0} = "unbounded". If the default were {@code 0}, an explicit
 * {@code --max-turn-seconds 0} would be indistinguishable from silence —
 * and the user could never turn the bound OFF.
 */
class MainMaxTurnSecondsR417Test {

    // ------------------------------------------------------------------
    // the resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R417: no flag and no env var means the MEASURED default (ten minutes)")
    void nothingSetMeansTheMeasuredDefault() {
        assertEquals(600, Main.resolveMaxTurnSeconds(-1, null));
        assertEquals(600, Main.resolveMaxTurnSeconds(-1, "  "));
    }

    @Test
    @DisplayName("R417: the environment variable sets the budget")
    void theEnvVarSetsTheBudget() {
        assertEquals(120, Main.resolveMaxTurnSeconds(-1, "120"));
        assertEquals(120, Main.resolveMaxTurnSeconds(-1, " 120 "));
    }

    @Test
    @DisplayName("R417: the flag wins over the environment")
    void theFlagWins() {
        assertEquals(30, Main.resolveMaxTurnSeconds(30, "120"));
    }

    @Test
    @DisplayName("R417: 0 means UNBOUNDED, and it is distinguishable from 'not set'")
    void zeroMeansUnbounded() {
        // ★ THE WHOLE POINT OF THE -1 DEFAULT ★ an explicit 0 must not
        // fall through to the default.
        assertEquals(0, Main.resolveMaxTurnSeconds(0, null));
        assertEquals(0, Main.resolveMaxTurnSeconds(0, "120"));
    }

    @Test
    @DisplayName("R417: a malformed environment value is ignored, not fatal")
    void aMalformedEnvValueIsIgnored() {
        assertEquals(600, Main.resolveMaxTurnSeconds(-1, "not-a-number"));
        assertEquals(600, Main.resolveMaxTurnSeconds(-1, "-5"));
    }

    // ------------------------------------------------------------------
    // the help text — the promise the user reads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R417: the help states the real default and the real stop reason")
    void theHelpTextIsTrue() throws Exception {
        String src = Files.readString(sourcePath(), StandardCharsets.UTF_8);
        int i = src.indexOf("--max-turn-seconds");
        assertTrue(i > 0, "the --max-turn-seconds option must exist");
        String option = src.substring(i, Math.min(src.length(), i + 1200));

        assertTrue(option.contains("600"),
                "the help must state the default the engine actually applies");
        assertTrue(option.contains("UNBOUNDED") || option.contains("unbounded"),
                "the help must say that 0 means unbounded");
        assertTrue(option.contains("AETHERCODE_MAX_TURN_SECONDS"),
                "the help must name the environment variable that sets the budget");
        assertTrue(option.contains("max_duration"),
                "the help must name the stop reason the user will see when the budget is hit");
    }

    @Test
    @DisplayName("R417: the option's default value is -1 (not set), not 0")
    void theOptionDefaultIsNotSet() throws Exception {
        String src = Files.readString(sourcePath(), StandardCharsets.UTF_8);
        int i = src.indexOf("int maxTurnSeconds = ");
        assertTrue(i > 0, "the maxTurnSeconds field must exist");
        String line = src.substring(i, src.indexOf(';', i));
        assertTrue(line.contains("= -1"),
                "the CLI default must be -1 (not set) — a 0 default would make an explicit "
                        + "'--max-turn-seconds 0' indistinguishable from silence: " + line);
        assertFalse(line.contains("= 0"),
                "the CLI default must not be 0: " + line);
    }

    private static Path sourcePath() {
        return Paths.get("src", "main", "java", "org", "aethercode", "cli", "Main.java");
    }
}
