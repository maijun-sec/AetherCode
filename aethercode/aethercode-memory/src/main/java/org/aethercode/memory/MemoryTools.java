package org.aethercode.memory;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.aethercode.tools.file.FileEditTool;
import org.aethercode.tools.file.FileReadTool;
import org.aethercode.tools.file.FileWriteTool;

import java.util.List;

/**
 * Build the tool set given to an agent that has memory enabled. Mirrors the TS rule that
 * an agent with a {@code memory} declaration in its definition gets {@code file_read},
 * {@code file_write}, {@code file_edit} injected automatically, in addition to the system
 * prompt section that explains how to use memory.
 */
public final class MemoryTools {

    private MemoryTools() {}

    public static List<Tool> forMemoryScope(String agentType, MemoryScope scope) {
        return List.of(
                FileReadTool.build(),
                FileWriteTool.build(),
                FileEditTool.build()
        );
    }
}
