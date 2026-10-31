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
