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
