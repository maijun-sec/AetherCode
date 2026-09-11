package org.aethercode.protocol.permissions;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * tests for {@link JsonRpcPermissionPrompter#classifyRisk}.
 * The end-to-end prompter flow is exercised by the daemon +
 * TUI integration tests (see the {@code aethercode-tui} project).
 */
class JsonRpcPermissionPrompterTest {

    @Test
    void classifyRisk_bashRmRfIsCritical() {
        Map<String, Object> input = new HashMap<>();
        input.put("command", "rm -rf /tmp/foo");
        assertEquals("critical", JsonRpcPermissionPrompter.classifyRisk("bash", input));
    }

    @Test
    void classifyRisk_bashSudoIsCritical() {
        Map<String, Object> input = new HashMap<>();
        input.put("command", "sudo apt install foo");
        assertEquals("critical", JsonRpcPermissionPrompter.classifyRisk("bash", input));
    }

    @Test
    void classifyRisk_bashMkfsIsCritical() {
        Map<String, Object> input = new HashMap<>();
        input.put("command", "mkfs.ext4 /dev/sda");
        assertEquals("critical", JsonRpcPermissionPrompter.classifyRisk("bash", input));
    }

    @Test
    void classifyRisk_bashLsIsHigh() {
        Map<String, Object> input = new HashMap<>();
        input.put("command", "ls -la");
        assertEquals("high", JsonRpcPermissionPrompter.classifyRisk("bash", input));
    }

    @Test
    void classifyRisk_bashShellIsHigh() {
        Map<String, Object> input = new HashMap<>();
        input.put("command", "echo hi");
        assertEquals("high", JsonRpcPermissionPrompter.classifyRisk("shell", input));
    }

    @Test
    void classifyRisk_fileReadIsLow() {
        assertEquals("low", JsonRpcPermissionPrompter.classifyRisk("file_read", new HashMap<>()));
    }

    @Test
    void classifyRisk_fileWriteIsMedium() {
        assertEquals("medium", JsonRpcPermissionPrompter.classifyRisk("file_write", new HashMap<>()));
    }

    @Test
    void classifyRisk_fileDeleteIsHigh() {
        assertEquals("high", JsonRpcPermissionPrompter.classifyRisk("file_delete", new HashMap<>()));
    }

    @Test
    void classifyRisk_unknownIsMedium() {
        assertEquals("medium", JsonRpcPermissionPrompter.classifyRisk("some_random_tool", new HashMap<>()));
    }

    @Test
    void classifyRisk_nullIsMedium() {
        assertEquals("medium", JsonRpcPermissionPrompter.classifyRisk(null, new HashMap<>()));
    }

    @Test
    void classifyRisk_bashDangerousForkBombIsCritical() {
        Map<String, Object> input = new HashMap<>();
        input.put("command", ":(){:|:&};:");
        assertEquals("critical", JsonRpcPermissionPrompter.classifyRisk("bash", input));
    }
}
