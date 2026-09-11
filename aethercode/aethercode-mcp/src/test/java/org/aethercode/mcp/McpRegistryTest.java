package org.aethercode.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpRegistryTest {

    @Test
    void listHasExpectedEntries() {
        List<String> names = McpRegistry.names();
        assertThat(names).contains("filesystem", "git", "fetch", "sqlite");
    }

    @Test
    void getReturnsByName() {
        McpRegistry.Entry e = McpRegistry.get("filesystem");
        assertThat(e).isNotNull();
        assertThat(e.type()).isEqualTo("stdio");
        assertThat(e.command()).isEqualTo("npx");
    }

    @Test
    void getMissingReturnsNull() {
        assertThat(McpRegistry.get("nonexistent")).isNull();
    }

    @Test
    void toMcpJsonRendersConfig() {
        Map<String, Object> json = McpRegistry.toMcpJson("filesystem", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = (Map<String, Object>) json.get("mcpServers");
        assertThat(servers).containsKey("filesystem");
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) servers.get("filesystem");
        assertThat(cfg.get("type")).isEqualTo("stdio");
        assertThat(cfg.get("command")).isEqualTo("npx");
    }

    @Test
    void toMcpJsonMergesExtra() {
        Map<String, Object> extra = Map.of("args", List.of("-y", "@custom/filesystem", "/tmp"));
        Map<String, Object> json = McpRegistry.toMcpJson("filesystem", extra);
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = (Map<String, Object>) json.get("mcpServers");
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) servers.get("filesystem");
        assertThat(cfg.get("args")).isEqualTo(List.of("-y", "@custom/filesystem", "/tmp"));
    }

    @Test
    void toMcpJsonUnknownThrows() {
        assertThatThrownBy(() -> McpRegistry.toMcpJson("ghost", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sseEntryHasUrlNotCommand() {
        McpRegistry.Entry e = McpRegistry.get("remote-fetch");
        assertThat(e.type()).isEqualTo("sse");
        assertThat(e.url()).isEqualTo("https://example.com/mcp/sse");
    }

    @Test
    void entryToConfigExcludesNulls() {
        McpRegistry.Entry e = McpRegistry.get("filesystem");
        Map<String, Object> cfg = e.toConfig();
        assertThat(cfg).containsKeys("type", "command", "args");
        assertThat(cfg).doesNotContainKey("url");
        assertThat(cfg).doesNotContainKey("host");
    }
}
