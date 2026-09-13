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
