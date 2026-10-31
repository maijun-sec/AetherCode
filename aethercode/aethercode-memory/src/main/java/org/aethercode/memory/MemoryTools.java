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
