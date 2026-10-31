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

package org.aethercode.deepagents.langchain_compat.langgraph_sdk;

import java.util.Map;
import java.util.Objects;

/**
 * LangGraph SDK {@code Run} record.
 *
 * <p>Java-native port of
 * {@code langgraph_sdk.schema.Run}. Mirrors the Python TypedDict
 * shape: a run is identified by a run id, thread id, status, and
 * carries the input/output payloads and timestamps.</p>
 */
public record Run(
        String runId,
        String threadId,
        String status,
        Map<String, Object> input,
        Map<String, Object> output,
        String error) {

    public Run {
        runId = Objects.requireNonNull(runId, "runId");
        threadId = Objects.requireNonNull(threadId, "threadId");
        status = status == null ? "pending" : status;
        input = input == null ? Map.of() : Map.copyOf(input);
        output = output == null ? Map.of() : Map.copyOf(output);
    }

    public boolean isTerminal() {
        return switch (status) {
            case "success", "error", "timeout", "interrupted" -> true;
            default -> false;
        };
    }
}
