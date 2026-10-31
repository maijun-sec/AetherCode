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

package org.aethercode.core.middleware;

import java.util.Set;

/**
 * Constants for the filesystem middleware tool names and tool
 * path arguments. Mirrors
 * {@code deepagents.middleware._fs_interrupt._FS_TOOL_PATH_ARGS}
 * so the rest of the filesystem-middleware surface (and the
 * interrupt-config glue) can share one source of truth.</p>
 */
public final class FilesystemToolNames {
    private FilesystemToolNames() {}

    public static final String LS = "ls";
    public static final String READ_FILE = "read_file";
    public static final String WRITE_FILE = "write_file";
    public static final String EDIT_FILE = "edit_file";
    public static final String DELETE = "delete";
    public static final String GLOB = "glob";
    public static final String GREP = "grep";
    public static final String EXECUTE = "execute";

    public static final Set<String> ALL = Set.of(
            LS, READ_FILE, WRITE_FILE, EDIT_FILE, DELETE, GLOB, GREP, EXECUTE);
}
