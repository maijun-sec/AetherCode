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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Testing models (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._testing_models}
 * module. The Java port exposes the surface the integration tests use
 * to drive the agent graph with deterministic responses; the full
 * implementation lands with the deepagents-core test fixtures.</p>
 */
public final class TestingModels {
    private TestingModels() {}

    /** A scripted response. */
    public record ScriptedResponse(String text, List<String> toolCalls) {
        public ScriptedResponse(String text) { this(text, List.of()); }
    }

    private final List<ScriptedResponse> queue = new ArrayList<>();

    /** Append a scripted response. */
    public void enqueue(ScriptedResponse response) {
        if (response != null) queue.add(response);
    }

    /** Take the next scripted response, or return an empty one. */
    public ScriptedResponse take() {
        if (queue.isEmpty()) return new ScriptedResponse("", List.of());
        return queue.remove(0);
    }

    /** Whether there are no queued responses. */
    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
