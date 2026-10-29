/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
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
