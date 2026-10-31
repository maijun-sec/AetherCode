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

package org.aethercode.core.notify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotifierTest {

    @Test
    void noOpReturnsFalse() {
        Notifier n = new Notifier.NoOp();
        assertThat(n.notify("t", "b", Notifier.Level.INFO)).isFalse();
    }

    @Test
    void recordingCapturesAll() {
        Notifier.Recording r = new Notifier.Recording();
        r.notify("a", "1", Notifier.Level.INFO);
        r.notify("b", "2", Notifier.Level.WARNING);
        assertThat(r.entries()).hasSize(2);
        assertThat(r.entries().get(0).title()).isEqualTo("a");
        assertThat(r.entries().get(1).level()).isEqualTo(Notifier.Level.WARNING);
    }

    @Test
    void recordingIsImmutableSnapshot() {
        Notifier.Recording r = new Notifier.Recording();
        r.notify("x", "y", Notifier.Level.ERROR);
        var snap1 = r.entries();
        r.notify("z", "w", Notifier.Level.SUCCESS);
        // snapshot taken before second call still shows just one entry
        assertThat(snap1).hasSize(1);
        assertThat(r.entries()).hasSize(2);
    }
}
