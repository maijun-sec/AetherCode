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

package org.aethercode.core.agent;

import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentOrchestratorTest {

    @Test
    void unknownAgentReturnsError() {
        Subagent.Registry r = new Subagent.Registry();
        SubagentOrchestrator o = new SubagentOrchestrator(r, null);
        Subagent.Result res = o.delegate(new Object(), "ghost", "do thing");
        assertThat(res.ok()).isFalse();
        assertThat(res.error()).contains("unknown subagent");
    }

    @Test
    void delegateRunsChildAndReturnsText() {
        Subagent.Registry r = new Subagent.Registry();
        r.register(new Subagent("researcher", "looks stuff up", "You research things.", List.of(), null));
        Subagent.EngineFactory f = (spec, parent) -> stubEngine("researcher",
                new StreamEvent.TextDelta("found it"), new StreamEvent.RunEnd("end_turn", List.of()));
        SubagentOrchestrator o = new SubagentOrchestrator(r, f);
        Subagent.Result res = o.delegate(new Object(), "researcher", "look up X");
        assertThat(res.ok()).isTrue();
        assertThat(res.text()).isEqualTo("found it");
        assertThat(res.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void delegateCountsToolCalls() {
        Subagent.Registry r = new Subagent.Registry();
        r.register(new Subagent("worker", "", "", List.of(), null));
        Subagent.EngineFactory f = (spec, parent) -> stubEngine("worker",
                new StreamEvent.ToolUseStart("id1", "bash", Map.of()),
                new StreamEvent.ToolUseStart("id2", "file_read", Map.of()),
                new StreamEvent.TextDelta("done"),
                new StreamEvent.RunEnd("end_turn", List.of()));
        SubagentOrchestrator o = new SubagentOrchestrator(r, f);
        Subagent.Result res = o.delegate(new Object(), "worker", "x");
        assertThat(res.toolCalls()).isEqualTo(2);
    }

    @Test
    void delegateCapturesException() {
        Subagent.Registry r = new Subagent.Registry();
        r.register(new Subagent("broken", "", "", List.of(), null));
        Subagent.EngineFactory f = (spec, parent) -> { throw new RuntimeException("factory kaboom"); };
        SubagentOrchestrator o = new SubagentOrchestrator(r, f);
        Subagent.Result res = o.delegate(new Object(), "broken", "x");
        assertThat(res.ok()).isFalse();
        assertThat(res.error()).contains("factory kaboom");
    }

    @Test
    void delegateAsyncReturnsSameResult() {
        Subagent.Registry r = new Subagent.Registry();
        r.register(new Subagent("ok", "", "", List.of(), null));
        Subagent.EngineFactory f = (spec, parent) -> stubEngine("ok",
                new StreamEvent.TextDelta("async-result"),
                new StreamEvent.RunEnd("end_turn", List.of()));
        SubagentOrchestrator o = new SubagentOrchestrator(r, f);
        CompletableFuture<Subagent.Result> fut = o.delegateAsync(new Object(), "ok", "task");
        Subagent.Result res = fut.join();
        assertThat(res.ok()).isTrue();
        assertThat(res.text()).isEqualTo("async-result");
    }

    @Test
    void registryRegisterAndLookup() {
        Subagent.Registry r = new Subagent.Registry();
        Subagent s = new Subagent("a", "b", "c", List.of(), "m");
        r.register(s);
        assertThat(r.get("a")).isSameAs(s);
        assertThat(r.contains("a")).isTrue();
        assertThat(r.contains("z")).isFalse();
        assertThat(r.names()).containsExactly("a");
    }

    @Test
    void defaultFactoryThrowsOnCoreLayer() {
        // core layer intentionally does not know about AetherCodeEngine — the
        // SDK module provides the canonical default. We assert that the core
        // throws an UnsupportedOperationException to force callers to supply
        // a real factory.
        try {
            SubagentOrchestrator.defaultFactory(new Subagent("x", "", "", List.of(), null), new Object());
            org.junit.jupiter.api.Assertions.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertThat(expected).hasMessageContaining("SDK");
        }
    }

    @Test
    void subagentRecordFields() {
        Subagent s = new Subagent("name", "desc", "prefix", List.of(), "model-x");
        assertThat(s.name()).isEqualTo("name");
        assertThat(s.description()).isEqualTo("desc");
        assertThat(s.systemPromptPrefix()).isEqualTo("prefix");
        assertThat(s.model()).isEqualTo("model-x");
        // null tools becomes empty list
        Subagent s2 = new Subagent("n", "d", "p", null, "m");
        assertThat(s2.tools()).isEmpty();
        // null prefix becomes empty
        Subagent s3 = new Subagent("n", "d", null, List.of(), "m");
        assertThat(s3.systemPromptPrefix()).isEmpty();
    }

    @Test
    void okAndErrorResultHelpers() {
        Subagent.Result ok = Subagent.Result.ok("n", "t", 3, 100);
        assertThat(ok.ok()).isTrue();
        assertThat(ok.error()).isNull();
        Subagent.Result er = Subagent.Result.error("n", "boom", 50);
        assertThat(er.ok()).isFalse();
        assertThat(er.error()).isEqualTo("boom");
    }

    @Test
    void registryIsCaseSensitive() {
        Subagent.Registry r = new Subagent.Registry();
        r.register(new Subagent("Foo", "", "", List.of(), null));
        assertThat(r.get("Foo")).isNotNull();
        assertThat(r.get("foo")).isNull();
    }

    @Test
    void stubEngineSessionIdAndTools() {
        Subagent.SubagentEngine e = stubEngine("a",
                new StreamEvent.RunEnd("end_turn", List.of()));
        assertThat(e.sessionId()).isEqualTo("stub:a");
        assertThat(e.tools()).isEmpty();
    }

    // ---- helpers ----

    private static Subagent.SubagentEngine stubEngine(String id, StreamEvent... events) {
        return new Subagent.SubagentEngine() {
            @Override
            public java.util.stream.Stream<StreamEvent> query(String task,
                                                              org.aethercode.core.llm.ChatClient chatClientOverride,
                                                              List<Tool> toolPoolOverride) {
                return Stream.of(events);
            }
            @Override public String sessionId() { return "stub:" + id; }
            @Override public List<Tool> tools() { return List.of(); }
        };
    }
}
