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
