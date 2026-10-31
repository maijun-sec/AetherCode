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
 * unit tests for the WebFetchTool hardening pass. The
 * network-touching tests are gated by the system property
 * {@code aethercode.test.network=true} so they only run when the
 * CI environment has outbound HTTP. The default test path
 * exercises only the validation logic (URL parsing, error
 * formatting) which is fully offline.
 */
class WebFetchToolTest {

    @Test
    void emptyUrlReturnsError() {
        Tool t = WebFetchTool.build();
        var res = t.call(Map.of("url", ""), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isTrue();
        assertThat(res.output().toString()).contains("url is required");
    }

    @Test
    void invalidSchemeReturnsError() {
        Tool t = WebFetchTool.build();
        var res = t.call(Map.of("url", "file:///etc/passwd"), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isTrue();
        assertThat(res.output().toString()).contains("http or https");
    }

    @Test
    void invalidUrlReturnsError() {
        Tool t = WebFetchTool.build();
        var res = t.call(Map.of("url", "not a url at all"), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isTrue();
    }

    @Test
    void readOnlyFlag() {
        // The web_fetch tool is read-only — confirm so a future
        // refactor doesn't accidentally make it destructive.
        assertThat(WebFetchTool.isReadOnly(Map.of())).isTrue();
    }

    /**
     * Real network test. Disabled by default. Enable with
     * {@code mvn test -Daethercode.test.network=true}. Hits
     * example.com (a stable, well-known test endpoint) and
     * verifies we get a 200 with HTML content.
     */
    @Test
    void liveFetch_exampleCom() {
        if (!"true".equals(System.getProperty("aethercode.test.network"))) {
            return;  // skip
        }
        Tool t = WebFetchTool.build();
        var res = t.call(Map.of("url", "https://example.com/"), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isFalse();
        String out = (String) res.output();
        assertThat(out).contains("Example Domain");
    }
}
