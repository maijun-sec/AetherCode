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

package org.aethercode.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R417 — the SDK's turn TIME budget.
 *
 * <h2>★ WHY THE SDK GETS A KNOB ★</h2>
 * <p>The SDK is how a downstream project embeds the engine, and until R417
 * it could bound a turn by COUNT but not by TIME — the two are not
 * interchangeable: a turn whose calls are all different never trips a
 * count, and R416's live matrix measured exactly that (104 and 142 model
 * calls in two scenarios whose work was already done).
 *
 * <h2>★ WHY "NOT SET" AND "UNBOUNDED" ARE DIFFERENT ★</h2>
 * <p>The builder's default is {@code -1} = "the caller said nothing", and
 * {@code 0} = "unbounded". If the default were {@code 0}, a caller could
 * never turn the bound OFF, and a caller that said nothing would silently
 * get an unbounded turn.
 */
class AetherCodeEngineR417Test {

    @Test
    @DisplayName("R417: the builder's default leaves the engine's own default in place")
    void theDefaultLeavesTheEngineDefault(@TempDir Path cwd) {
        AetherCodeEngine e = AetherCodeEngine.builder().cwd(cwd).build();
        assertThat(e.queryEngine().maxTurnMillis())
                .as("the SDK must not silently impose its own budget")
                .isEqualTo(org.aethercode.core.engine.QueryEngine.DEFAULT_MAX_TURN_MILLIS);
    }

    @Test
    @DisplayName("R417: the builder sets the engine's time budget")
    void theBuilderSetsTheBudget(@TempDir Path cwd) {
        AetherCodeEngine e = AetherCodeEngine.builder()
                .cwd(cwd)
                .maxTurnSeconds(90)
                .build();
        assertThat(e.queryEngine().maxTurnMillis()).isEqualTo(90_000L);
    }

    @Test
    @DisplayName("R417: 0 means UNBOUNDED, and it is not the same as 'not set'")
    void zeroMeansUnbounded(@TempDir Path cwd) {
        AetherCodeEngine e = AetherCodeEngine.builder()
                .cwd(cwd)
                .maxTurnSeconds(0)
                .build();
        assertThat(e.queryEngine().maxTurnMillis())
                .as("an explicit 0 must reach the engine as 0, not be dropped as 'unset'")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("R417: a negative value is 'not set', not 'unbounded'")
    void aNegativeValueIsNotSet(@TempDir Path cwd) {
        AetherCodeEngine e = AetherCodeEngine.builder()
                .cwd(cwd)
                .maxTurnSeconds(-1)
                .build();
        assertThat(e.queryEngine().maxTurnMillis())
                .as("-1 means the caller said nothing ⇒ the engine's own default stands")
                .isEqualTo(org.aethercode.core.engine.QueryEngine.DEFAULT_MAX_TURN_MILLIS);
    }
}
