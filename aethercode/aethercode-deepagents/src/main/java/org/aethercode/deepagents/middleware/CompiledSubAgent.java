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

package org.aethercode.deepagents.middleware;

import java.util.Objects;

/**
 * A pre-compiled subagent spec.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.subagents.CompiledSubAgent}. The
 * record holds the (name, description) plus a pre-compiled runnable
 * the consumer built ahead of time. The Java port uses
 * {@link java.util.concurrent.Callable} for the runnable so the type
 * signature stays concrete; consumers that need a richer graph
 * runtime can wrap their graph in a {@code Callable}.</p>
 */
public record CompiledSubAgent(String name, String description,
                               java.util.concurrent.Callable<SubAgentResult> runnable) {

    public CompiledSubAgent {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        Objects.requireNonNull(runnable, "runnable");
    }

    public SubAgentResult invoke() throws Exception {
        return runnable.call();
    }
}
