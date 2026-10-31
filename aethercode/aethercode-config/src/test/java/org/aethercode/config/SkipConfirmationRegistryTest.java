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

package org.aethercode.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * per-session skip-confirmation counter.
 */
class SkipConfirmationRegistryTest {
    private static AetherCodeConfig.MemoryConfig defaultMemory() { return new AetherCodeConfig.MemoryConfig(); }

    @Test
    void emptyRegistry_neverConsumes() {
        SkipConfirmationRegistry r = new SkipConfirmationRegistry();
        assertThat(r.consumeOne("s1")).isFalse();
        assertThat(r.remaining("s1")).isEqualTo(0);
    }

    @Test
    void setThenConsume_decrementsCounter() {
        SkipConfirmationRegistry r = new SkipConfirmationRegistry();
        r.set("s1", 3);
        assertThat(r.remaining("s1")).isEqualTo(3);
        assertThat(r.consumeOne("s1")).isTrue();
        assertThat(r.consumeOne("s1")).isTrue();
        assertThat(r.remaining("s1")).isEqualTo(1);
        assertThat(r.consumeOne("s1")).isTrue();
        assertThat(r.remaining("s1")).isEqualTo(0);
        // Once exhausted, never consumes again.
        assertThat(r.consumeOne("s1")).isFalse();
    }

    @Test
    void setZeroOrNegative_clearsCounter() {
        SkipConfirmationRegistry r = new SkipConfirmationRegistry();
        r.set("s1", 5);
        assertThat(r.remaining("s1")).isEqualTo(5);
        r.set("s1", 0);
        assertThat(r.remaining("s1")).isEqualTo(0);
        r.set("s1", 5);
        r.set("s1", -1);
        assertThat(r.remaining("s1")).isEqualTo(0);
    }

    @Test
    void clear_removesSession() {
        SkipConfirmationRegistry r = new SkipConfirmationRegistry();
        r.set("s1", 5);
        r.clear("s1");
        assertThat(r.remaining("s1")).isEqualTo(0);
        assertThat(r.consumeOne("s1")).isFalse();
    }

    @Test
    void differentSessions_haveIndependentCounters() {
        SkipConfirmationRegistry r = new SkipConfirmationRegistry();
        r.set("s1", 2);
        r.set("s2", 5);
        assertThat(r.consumeOne("s1")).isTrue();
        assertThat(r.consumeOne("s1")).isTrue();
        assertThat(r.consumeOne("s1")).isFalse();
        // s2 is untouched.
        assertThat(r.remaining("s2")).isEqualTo(5);
    }

    @Test
    void nullSessionId_isNoOp() {
        SkipConfirmationRegistry r = new SkipConfirmationRegistry();
        r.set(null, 5);
        assertThat(r.consumeOne(null)).isFalse();
        assertThat(r.remaining(null)).isEqualTo(0);
        r.clear(null); // does not throw
    }

    @Test
    void applyDefault_skipConfirmationTrue_meansInfinite() {
        SkipConfirmationRegistry r = new SkipConfirmationRegistry();
        AetherCodeConfig cfg = new AetherCodeConfig(1, new PermissionMatrix(),
                /* skipConfirmation */ true, 0, "design-first", null, 5, defaultMemory());
        r.applyDefault("s1", cfg);
        // consume a few hundred times; should never run out.
        for (int i = 0; i < 500; i++) {
            assertThat(r.consumeOne("s1")).isTrue();
        }
        assertThat(r.remaining("s1")).isGreaterThan(0);
    }

    @Test
    void applyDefault_skipConfirmationRounds_isLoaded() {
        SkipConfirmationRegistry r = new SkipConfirmationRegistry();
        AetherCodeConfig cfg = new AetherCodeConfig(1, new PermissionMatrix(),
                false, 7, "design-first", null, 5, defaultMemory());
        r.applyDefault("s1", cfg);
        assertThat(r.remaining("s1")).isEqualTo(7);
    }

    @Test
    void applyDefault_zeroOrNull_isNoOp() {
        SkipConfirmationRegistry r = new SkipConfirmationRegistry();
        r.applyDefault("s1", AetherCodeConfig.defaults());
        assertThat(r.remaining("s1")).isEqualTo(0);
        r.applyDefault("s1", null);
        assertThat(r.remaining("s1")).isEqualTo(0);
    }
}
