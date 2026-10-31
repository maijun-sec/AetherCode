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

package org.aethercode.sdk;

import org.aethercode.core.concurrency.EngineStats;

/**
 * thrown by {@link AetherCodeEngine#query(String)} when
 * the engine is backpressured (memory above the configured
 * threshold). The {@code AetherCodeMethods.query} RPC catches
 * this and surfaces it as a structured JSON-RPC error so the
 * desktop can render a "System busy, please retry later" pill and (optionally)
 * a "Reduce concurrency profile" affordance.
 *
 * <p>This is a {@link RuntimeException} so it propagates out of
 * the streaming {@code query()} return value. The desktop's
 * store handler is already in the path that handles RPC
 * errors, so no separate try/catch is required at the call site.
 */
public class BackpressureException extends RuntimeException {

    private final EngineStats stats;

    public BackpressureException(String message, EngineStats stats) {
        super(message);
        this.stats = stats;
    }

    /** Snapshot of the engine stats at the time the query was
     *  refused. Useful for the desktop's diagnostic pill
     *  ("Memory 92% · 1 query in flight · 0 tools in flight"). */
    public EngineStats stats() { return stats; }
}
