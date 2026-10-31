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

package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Tool stream (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._tool_stream}
 * module. The Java port exposes the surface used to surface tool-call
 * events to the TUI as they happen; the full implementation lands
 * with the deepagents-core middleware port.</p>
 */
public final class ToolStream {
    private ToolStream() {}

    private static final Logger LOG = LoggerFactory.getLogger(ToolStream.class);

    /** A tool-stream event. */
    public record Event(
            String toolCallId,
            String toolName,
            Object args,
            Object result) {
    }

    /** Subscribe to the tool stream. Returns a no-op completion. */
    public static CompletionStage<Void> subscribe(java.util.function.Consumer<Event> listener) {
        if (listener != null) {
            LOG.debug("ToolStream.subscribe (stub)");
        }
        return CompletableFuture.completedFuture(null);
    }
}
