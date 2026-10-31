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

package org.aethercode.core.fs.backend;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Pluggable store for the {@code files} channel of the agent state.
 *
 * <p>Java-native equivalent of the langgraph
 * {@code CONFIG_KEY_READ}/{@code CONFIG_KEY_SEND} pair. The
 * {@link StateBackend} reads/writes the {@code files} map through this
 * interface; the default in-memory implementation lives in
 * {@code InMemoryFilesStateStore}, and the graph runtime can plug in a
 * channel-bound one.</p>
 */
public interface FilesStateStore {

    /**
     * Read the current files map.
     *
     * <p>{@code fresh} applies any pending task writes through the
     * channel's reducer before returning, giving read-your-writes
     * semantics within a single superstep — e.g. a tool that writes a
     * file and then reads it back.</p>
     */
    Map<String, FileData> read(boolean fresh);

    /** Queue a write to the files map. */
    void send(Map<String, FileData> update);
}
