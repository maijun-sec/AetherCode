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
 * tests for the prompt-text skip-confirmation detector.
 */
class SkipConfirmationDetectorTest {

    @Test
    void emptyPrompt_returnsZero() {
        assertThat(SkipConfirmationDetector.detect(null)).isEqualTo(0);
        assertThat(SkipConfirmationDetector.detect("")).isEqualTo(0);
        assertThat(SkipConfirmationDetector.detect("   ")).isEqualTo(0);
    }

    @Test
    void canonicalPattern_returnsCount() {
        assertThat(SkipConfirmationDetector.detect(
                "no confirmation needed for next 5 rounds, go!"))
                .isEqualTo(5);
    }

    @Test
    void shortConfirmPhrase_returnsCount() {
        assertThat(SkipConfirmationDetector.detect("skip confirm for next 3 rounds"))
                .isEqualTo(3);
    }

    @Test
    void noNeedToConfirm_returnsCount() {
        assertThat(SkipConfirmationDetector.detect("no need to confirm for next 10 rounds"))
                .isEqualTo(10);
    }

    @Test
    void autoAllow_returnsCount() {
        assertThat(SkipConfirmationDetector.detect("auto-allow for next 4 rounds"))
                .isEqualTo(4);
    }

    @Test
    void caseInsensitive() {
        assertThat(SkipConfirmationDetector.detect(
                "No Confirmation Needed For Next 7 Rounds"))
                .isEqualTo(7);
        assertThat(SkipConfirmationDetector.detect(
                "SKIP CONFIRMATION FOR NEXT 2 ROUNDS"))
                .isEqualTo(2);
    }

    @Test
    void singularRound_returnsOne() {
        assertThat(SkipConfirmationDetector.detect("no confirmation for the next round"))
                .isEqualTo(1);
    }

    @Test
    void irrelevantPrompt_returnsZero() {
        assertThat(SkipConfirmationDetector.detect("write me a sort algorithm"))
                .isEqualTo(0);
        assertThat(SkipConfirmationDetector.detect("the next 5 lines should be empty"))
                .as("'next 5 lines' is not rounds").isEqualTo(0);
    }

    @Test
    void embeddedInLongerPrompt_stillMatches() {
        String prompt = """
                Implement the new login flow.

                Important: no confirmation needed for next 8 rounds, just keep going.
                After that, ask me before any file_write.
                """;
        assertThat(SkipConfirmationDetector.detect(prompt)).isEqualTo(8);
    }
}
