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

package org.aethercode.talon.runtime;

import org.aethercode.talon.interfaces.AgentRequest;
import org.aethercode.talon.interfaces.AgentResult;
import org.aethercode.talon.interfaces.AgentRuntime;

import java.util.concurrent.CompletableFuture;

/**
 * Small placeholder runtime for host bootstrapping and tests.
 *
 * <p>Java-native port of
 * {@code deepagents_talon.runtime.EchoAgentRuntime}. Returns the request
 * text as a trivial response so the host loop can drive messages end
 * to end without a real chat model attached.</p>
 */
public class EchoAgentRuntime implements AgentRuntime {

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<AgentResult> invoke(AgentRequest request) {
        return CompletableFuture.completedFuture(new AgentResult(request.text()));
    }
}
