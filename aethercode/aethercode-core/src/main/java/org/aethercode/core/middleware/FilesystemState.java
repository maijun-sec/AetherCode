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

import org.aethercode.core.runtime.AgentState;

import java.util.Map;

/**
 * Marker state schema for the FilesystemMiddleware.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware.filesystem.FilesystemState} TypedDict
 * &mdash; an {@link AgentState} that carries a top-level
 * {@code files} channel for ephemeral filesystem contents. The
 * Java port stores files on the backend (not in the state
 * directly), but exposing this type as the
 * {@link FilesystemMiddleware#stateSchema()} value mirrors the
 * Python port's "in-process state files" behavior for the
 * {@code FilesystemState}-eligible backends ({@code StateBackend}
 * and {@code CompositeBackend} whose default is a
 * {@code StateBackend}).</p>
 *
 * <p>The Java port's {@link FilesystemState} is a thin record
 * carrying a {@code Map<String, FileData> files} field; the
 * runtime treats it as a marker type for tests and
 * {@code stateSchema} selection, not for in-memory storage.</p>
 */
public record FilesystemState(
        AgentState.AgentStateMap files
) {
    public FilesystemState {
        files = files == null ? new AgentState.AgentStateMap(Map.of()) : files;
    }

    /** Empty files map. */
    public static FilesystemState empty() {
        return new FilesystemState(new AgentState.AgentStateMap(Map.of()));
    }
}
