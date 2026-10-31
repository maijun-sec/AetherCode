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

package org.aethercode.core.tips;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TipsTest {

    @Test
    void allReturnsNonEmpty() {
        assertThat(Tips.all()).isNotEmpty();
    }

    @Test
    void sizeMatchesList() {
        assertThat(Tips.size()).isEqualTo(Tips.all().size());
        assertThat(Tips.size()).isGreaterThan(5);
    }

    @Test
    void allIsConsistent() {
        Set<String> first = new HashSet<>(Tips.all());
        Set<String> second = new HashSet<>(Tips.all());
        assertThat(first).isEqualTo(second);
    }

    @Test
    void randomReturnsValidTip() {
        for (int i = 0; i < 50; i++) {
            String t = Tips.random();
            assertThat(t).isNotBlank();
            assertThat(Tips.all()).contains(t);
        }
    }

    @Test
    void randomWithSeedIsDeterministic() {
        Random r1 = new Random(42);
        Random r2 = new Random(42);
        for (int i = 0; i < 10; i++) {
            assertThat(Tips.random(r1)).isEqualTo(Tips.random(r2));
        }
    }

    @Test
    void atReturnsExpectedIndex() {
        String first = Tips.all().get(0);
        assertThat(Tips.at(0)).isEqualTo(first);
    }

    @Test
    void atWrapsWithNegativeIndex() {
        int n = Tips.size();
        assertThat(Tips.at(-1)).isEqualTo(Tips.all().get(n - 1));
    }

    @Test
    void atWrapsAroundSize() {
        assertThat(Tips.at(Tips.size())).isEqualTo(Tips.at(0));
        assertThat(Tips.at(Tips.size() + 5)).isEqualTo(Tips.at(5));
    }
}
