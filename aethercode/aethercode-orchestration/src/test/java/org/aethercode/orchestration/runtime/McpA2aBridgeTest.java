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

package org.aethercode.orchestration.runtime;

import org.aethercode.orchestration.runtime.McpA2aBridge.A2aMessage;
import org.aethercode.orchestration.runtime.McpA2aBridge.McpToolCall;
import org.aethercode.orchestration.runtime.McpA2aBridge.McpToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpA2aBridgeTest {

    @Test
    void mcpToA2aMessageHasUserRoleAndTextHeader() {
        var b = new McpA2aBridge();
        var m = b.toA2aMessage(new McpToolCall("file_read", Map.of("file_path", "x.txt")));
        assertEquals("user", m.role());
        // first part is the "tool:<name>" header
        assertEquals("text", m.parts().get(0).get("type"));
        assertEquals("tool:file_read", m.parts().get(0).get("content"));
        // second part is the data
        assertEquals("data", m.parts().get(1).get("type"));
    }

    @Test
    void mcpResultToA2aHasAgentRole() {
        var b = new McpA2aBridge();
        var m = b.toA2aMessage(new McpToolResult("file_read", "contents", false));
        assertEquals("agent", m.role());
        assertEquals("text", m.parts().get(0).get("type"));
    }

    @Test
    void errorResultIncludesErrorFlag() {
        var b = new McpA2aBridge();
        var m = b.toA2aMessage(new McpToolResult("file_read", "boom", true));
        assertEquals(Boolean.TRUE, m.parts().get(0).get("error"));
    }

    @Test
    void a2aToMcpRoundTrip() {
        var b = new McpA2aBridge();
        var original = new McpToolCall("bash", Map.of("command", "ls"));
        var a2a = b.toA2aMessage(original);
        var back = b.toMcpToolCall(a2a);
        assertNotNull(back);
        assertEquals("bash", back.name());
        assertEquals("ls", back.args().get("command"));
    }

    @Test
    void emptyA2aMessageReturnsNull() {
        var b = new McpA2aBridge();
        assertNull(b.toMcpToolCall(new A2aMessage("user", List.of())));
    }

    @Test
    void textOnlyA2aMessageBecomesToolName() {
        var b = new McpA2aBridge();
        var back = b.toMcpToolCall(new A2aMessage("user", List.of(
            Map.of("type", "text", "content", "echo")
        )));
        assertNotNull(back);
        assertEquals("echo", back.name());
    }
}
