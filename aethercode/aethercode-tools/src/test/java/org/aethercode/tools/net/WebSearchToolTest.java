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
