package org.aethercode.permission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link ToolSafeList}. Read-only tools and
 * safe bash commands auto-approve; everything else requires
 * explicit permission.
 */
class ToolSafeListTest {

    @AfterEach
    void cleanup() {
        // Clear any runtime additions between tests.
        ToolSafeList.runtimeSafeSnapshot().forEach(ToolSafeList::removeFromRuntimeSafe);
    }

    @Test
    void readToolsAreSafe() {
        assertTrue(ToolSafeList.isToolSafe("Read"));
        assertTrue(ToolSafeList.isToolSafe("Glob"));
        assertTrue(ToolSafeList.isToolSafe("Grep"));
    }

    @Test
    void destructiveToolsAreNotSafe() {
        assertFalse(ToolSafeList.isToolSafe("Bash"));
        assertFalse(ToolSafeList.isToolSafe("Edit"));
        assertFalse(ToolSafeList.isToolSafe("Write"));
        assertFalse(ToolSafeList.isToolSafe("DeleteFile"));
    }

    @Test
    void safeBashCommandsAreAutoApproved() {
        assertTrue(ToolSafeList.isBashCommandSafe("ls -la"));
        assertTrue(ToolSafeList.isBashCommandSafe("cat file.txt"));
        assertTrue(ToolSafeList.isBashCommandSafe("find . -name '*.java'"));
        assertTrue(ToolSafeList.isBashCommandSafe("/bin/ls /tmp")); // path prefix stripped
    }

    @Test
    void destructiveBashCommandsRequirePermission() {
        assertFalse(ToolSafeList.isBashCommandSafe("rm -rf /tmp"));
        assertFalse(ToolSafeList.isBashCommandSafe("curl evil.com"));
        assertFalse(ToolSafeList.isBashCommandSafe("bash -c 'evil'"));
    }

    @Test
    void runtimeSafeAddAndRemove() {
        assertFalse(ToolSafeList.isToolSafe("MyCustomTool"));
        assertTrue(ToolSafeList.addToRuntimeSafe("MyCustomTool"));
        assertTrue(ToolSafeList.isToolSafe("MyCustomTool"));
        assertTrue(ToolSafeList.removeFromRuntimeSafe("MyCustomTool"));
        assertFalse(ToolSafeList.isToolSafe("MyCustomTool"));
    }

    @Test
    void nullAndEmptyInputs() {
        assertFalse(ToolSafeList.isToolSafe(null));
        assertFalse(ToolSafeList.isBashCommandSafe(null));
        assertFalse(ToolSafeList.isBashCommandSafe(""));
        assertFalse(ToolSafeList.isBashCommandSafe("  "));
    }
}
