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

import org.aethercode.core.agent.Subagent;
import org.aethercode.core.agent.SubagentOrchestrator;

/**
 * SDK-side glue for the subagent framework. Wraps the core
 * {@link SubagentOrchestrator} with an {@link Subagent.EngineFactory} that
 * knows how to fork an {@link AetherCodeEngine} via {@code toBuilder()}.
 */
public final class AetherCodeSubagents {

    private AetherCodeSubagents() {}

    /**
     * Build an orchestrator that creates child engines by cloning the parent
     * engine with the subagent's tools + system-prompt prefix. This is the
     * canonical "fork and run" factory.
     */
    public static SubagentOrchestrator orchestrator(Subagent.Registry registry) {
        return new SubagentOrchestrator(registry, AetherCodeSubagents::defaultFactory);
    }

    public static Subagent.SubagentEngine defaultFactory(Subagent spec, Object parentCtx) {
        if (!(parentCtx instanceof AetherCodeEngine parent)) {
            throw new IllegalArgumentException(
                    "AetherCodeSubagents.defaultFactory requires AetherCodeEngine parent, got " +
                    (parentCtx == null ? "null" : parentCtx.getClass().getName()));
        }
        return parent.toBuilder()
                .tools(spec.tools())
                .systemPrompt(spec.systemPromptPrefix())
                .sessionId(parent.appState().sessionId() + "::" + spec.name())
                .build();
    }
}
