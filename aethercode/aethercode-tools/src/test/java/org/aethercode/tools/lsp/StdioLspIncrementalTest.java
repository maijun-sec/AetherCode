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

package org.aethercode.tools.lsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StdioLspIncrementalTest {

    @Test
    void incrementalFactoryHelpers() {
        StdioLspSession.IncrementalChange full = StdioLspSession.IncrementalChange.FULL;
        assertThat(full.kind).isEqualTo(StdioLspSession.IncrementalChange.Kind.FULL);
        StdioLspSession.IncrementalChange range = StdioLspSession.IncrementalChange.range(2, 5, "hello");
        assertThat(range.kind).isEqualTo(StdioLspSession.IncrementalChange.Kind.RANGE);
        assertThat(range.startLine).isEqualTo(2);
        assertThat(range.endLine).isEqualTo(5);
        assertThat(range.text).isEqualTo("hello");
        StdioLspSession.IncrementalChange insert = StdioLspSession.IncrementalChange.insert(7, 3, "x");
        assertThat(insert.kind).isEqualTo(StdioLspSession.IncrementalChange.Kind.INSERT);
        assertThat(insert.startCol).isEqualTo(3);
    }
}
