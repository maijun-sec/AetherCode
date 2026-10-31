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

package org.aethercode.tools.net;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ScholarSearchTool} -- the {@code google_scholar} tool surface.
 *
 * <p>The live HTTP path is covered by {@link GoogleScholarClientTest}; here
 * we focus on the tool's input validation, output shape, and error paths
 * (which don't require network).</p>
 */
class ScholarSearchToolTest {

    @Test
    void emptyQueryReturnsError() {
        Tool t = ScholarSearchTool.build();
        var res = t.call(Map.of("query", ""), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isTrue();
        assertThat(res.output().toString()).contains("query is required");
    }

    @Test
    void missingQueryReturnsError() {
        Tool t = ScholarSearchTool.build();
        var res = t.call(Map.of(), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isTrue();
        assertThat(res.output().toString()).contains("query is required");
    }

    @Test
    void blankQueryReturnsError() {
        Tool t = ScholarSearchTool.build();
        var res = t.call(Map.of("query", "   "), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isTrue();
        assertThat(res.output().toString()).contains("query is required");
    }

    @Test
    void toolNameIsGoogleScholar() {
        // The tool is intentionally named "google_scholar" because that is
        // what the model expects; the backend is Semantic Scholar.
        assertThat(ScholarSearchTool.NAME).isEqualTo("google_scholar");
    }

    @Test
    void toolIsReadOnly() {
        // The tool never mutates state; it is always safe under
        // ACCEPT_EDITS / read-only permission modes.
        assertThat(ScholarSearchTool.isReadOnly(Map.of("query", "x"))).isTrue();
        assertThat(ScholarSearchTool.isReadOnly(Map.of())).isTrue();
    }

    @Test
    void toolIsRegisteredInStandardTools() {
        // Sanity check that the tool is wired into the default tool pool.
        // The list contains many tools; just assert ours is among them.
        boolean found = org.aethercode.tools.StandardTools.all().stream()
                .anyMatch(t -> ScholarSearchTool.NAME.equals(t.name()));
        assertThat(found)
                .as("ScholarSearchTool must be registered in StandardTools.all()")
                .isTrue();
    }
}
