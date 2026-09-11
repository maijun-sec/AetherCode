package org.aethercode.tools.net;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchToolTest {

    @Test
    void missingApiKeyReturnsClearError() {
        Tool t = WebSearchTool.build();
        var res = t.call(Map.of("query", "java streams"), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isTrue();
        assertThat(res.output().toString()).contains("AETHERCODE_BRAVE_API_KEY");
    }

    @Test
    void emptyQueryReturnsError() {
        Tool t = WebSearchTool.build();
        var res = t.call(Map.of("query", ""), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isTrue();
        assertThat(res.output().toString()).contains("query is required");
    }
}
