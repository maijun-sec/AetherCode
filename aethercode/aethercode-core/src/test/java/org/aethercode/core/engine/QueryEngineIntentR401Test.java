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

package org.aethercode.core.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.aethercode.core.app.AppState;
import org.aethercode.core.intent.IntentClassifier;
import org.aethercode.core.intent.RunContext;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.profile.Profile;
import org.aethercode.core.profile.ProfileRegistry;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R401: the engine honours the turn's POSTURE.
 *
 * <p>Three things are pinned here, and they are the three halves of
 * "structural, not a prompt":
 *
 * <ol>
 *   <li>the posture directive is INJECTED into the per-turn system
 *       prompt — and it sits BEFORE the R737 language lock, so the
 *       language lock stays the last block;</li>
 *   <li>the MODEL IS SHOWN the pruned tool table — a forbidden tool is
 *       not callable because it is not in the list;</li>
 *   <li>the EXECUTOR resolves the tool from the same pruned pool — so
 *       even a model that emits a forbidden tool anyway gets a REFUSAL
 *       instead of a write.</li>
 * </ol>
 *
 * <p>All three use the REAL {@link StreamingToolExecutor} — a stub
 * would answer the question "was the stub called?" instead of "was
 * the write prevented?".
 */
class QueryEngineIntentR401Test {

    // ------------------------------------------------------------------
    // a recording chat client
    // ------------------------------------------------------------------

    private static final class RecordingClient implements ChatClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile String systemPrompt;
        volatile List<Tool> tools;
        /** the tool the model asks for on its FIRST turn (null = none). */
        final String toolToCall;

        RecordingClient(String toolToCall) {
            this.toolToCall = toolToCall;
        }

        @Override
        public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
            int n = calls.incrementAndGet();
            this.systemPrompt = systemPrompt;
            this.tools = tools;
            if (toolToCall != null && n == 1) {
                ContentBlock.ToolUseBlock tu =
                        new ContentBlock.ToolUseBlock("call-1", toolToCall, Map.of());
                return Stream.of(
                        new StreamEvent.RunStart("run-1", "test-model"),
                        new StreamEvent.ToolUseStart(tu.id(), tu.name(), tu.input()),
                        new StreamEvent.RunEnd("tool_use", List.of(tu)));
            }
            return Stream.of(
                    new StreamEvent.RunStart("run-" + n, "test-model"),
                    new StreamEvent.TextDelta("done"),
                    new StreamEvent.RunEnd("end_turn", List.of(new ContentBlock.TextBlock("done"))));
        }

        @Override public String modelId() { return "test-model"; }
    }

    private static Tool tool(String name, AtomicInteger invocations) {
        return org.aethercode.core.tool.Tools.build(new org.aethercode.core.tool.ToolDef(
                name, name, Map.of("type", "object", "properties", Map.of()),
                (input, ctx) -> {
                    invocations.incrementAndGet();
                    return CompletableFuture.completedFuture(Tool.ToolResult.of("wrote " + name));
                }));
    }

    private static QueryEngine engineWith(ChatClient client, List<Tool> tools) {
        AppState state = new AppState("s1", Path.of(""));
        state.toolPool().addAll(tools);
        PermissionPolicy allowAll = (tool, input, ctx) ->
                CompletableFuture.completedFuture(
                        new org.aethercode.core.permission.PermissionResult.Allow(Map.of()));
        // the REAL executor — a stub would hide the very thing under
        // test ("was the write actually prevented?").
        StreamingToolExecutor exec = new StreamingToolExecutor(allowAll, 1);
        return new QueryEngine(state, client, allowAll, "BASE PROMPT", m -> {}, exec, null, null);
    }

    private static List<String> toolNames(List<Tool> tools) {
        List<String> out = new ArrayList<>();
        for (Tool t : tools) out.add(t.name());
        return out;
    }

    private static RunContext ctxFor(IntentClassifier.Intent intent, List<Tool> pool) {
        // ★ R411: THE PRODUCTION PATH ★ the posture is resolved from the
        // WHOLE verdict (domain + capability), not from the kind alone —
        // resolving only the kind would test a path production does not
        // use, and it would stay green if that path broke.
        Profile p = ProfileRegistry.forIntent(intent);
        // The SAME tool instances the test counts on must be the ones
        // the executor resolves — otherwise the test counts a
        // different object than the one that ran.
        return new RunContext(intent, p, ProfileRegistry.prune(pool, p));
    }

    // ------------------------------------------------------------------
    // 1. the directive is injected, BEFORE the language lock
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void thePostureDirectiveIsInjectedBeforeTheLanguageLock() {
        // A Chinese prompt so R737's language lock fires — the whole
        // point is the ORDER: the posture is the block right before it.
        String prompt = "这个仓库的 checkpoint 机制是如何设计的？";
        IntentClassifier.Intent intent = IntentClassifier.classify(prompt);
        assertEquals(IntentClassifier.Kind.ANSWER, intent.kind(), intent.reason());

        RecordingClient client = new RecordingClient(null);
        List<Tool> pool = List.of(
                tool("file_read", new AtomicInteger()),
                tool("file_write", new AtomicInteger()));
        QueryEngine engine = engineWith(client, pool);
        engine.query(prompt, null, ctxFor(intent, pool)).forEach(e -> {});

        String sys = client.systemPrompt;
        assertNotNull(sys, "the model was never called");
        int posture = sys.indexOf("# Task posture (R401");
        int language = sys.indexOf("# Response language (R737");
        assertTrue(posture >= 0, "the posture directive was not injected:\n" + sys);
        assertTrue(language >= 0, "the R737 language lock did not fire:\n" + sys);
        assertTrue(posture < language,
                "the posture directive must come BEFORE the language lock "
                        + "(the language lock has to stay the last block)");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aTurnWithoutAPostureHasNoPostureBlock() {
        // The pre-R401 shape must be untouched: no posture, no
        // directive, the full tool table.
        RecordingClient client = new RecordingClient(null);
        QueryEngine engine = engineWith(client, List.of(
                tool("file_read", new AtomicInteger()),
                tool("file_write", new AtomicInteger())));
        engine.query("hello").forEach(e -> {});
        assertFalse(client.systemPrompt.contains("# Task posture (R401"),
                "a posture-free turn must not carry a posture block");
        assertTrue(toolNames(client.tools).contains("file_write"),
                "a posture-free turn must see the full tool table");
    }

    // ------------------------------------------------------------------
    // 2. the model is shown the pruned table
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void theModelIsShownThePrunedToolTable() {
        String prompt = "这个仓库的 checkpoint 机制是如何设计的？";
        IntentClassifier.Intent intent = IntentClassifier.classify(prompt);
        RecordingClient client = new RecordingClient(null);
        List<Tool> pool = List.of(
                tool("file_read", new AtomicInteger()),
                tool("file_write", new AtomicInteger()),
                tool("grep", new AtomicInteger()));
        QueryEngine engine = engineWith(client, pool);
        engine.query(prompt, null, ctxFor(intent, pool)).forEach(e -> {});

        List<String> shown = toolNames(client.tools);
        assertTrue(shown.contains("file_read"), shown.toString());
        assertTrue(shown.contains("grep"), shown.toString());
        assertFalse(shown.contains("file_write"),
                "a read-only posture must not show file_write to the model: " + shown);
    }

    // ------------------------------------------------------------------
    // 3. the executor resolves from the same pruned pool
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aPrunedToolIsNotExecutableEvenWhenTheModelCallsIt() {
        // The model asks for file_write anyway (it saw one earlier, or
        // it hallucinated it). The executor resolves from the pruned
        // pool, so the tool is NOT FOUND and the write never happens.
        AtomicInteger writeCalls = new AtomicInteger();
        String prompt = "这个仓库的 checkpoint 机制是如何设计的？";
        IntentClassifier.Intent intent = IntentClassifier.classify(prompt);

        RecordingClient client = new RecordingClient("file_write");
        List<Tool> pool = List.of(
                tool("file_read", new AtomicInteger()),
                tool("file_write", writeCalls));
        QueryEngine engine = engineWith(client, pool);
        List<StreamEvent> events = new ArrayList<>();
        engine.query(prompt, null, ctxFor(intent, pool)).forEach(events::add);

        assertEquals(0, writeCalls.get(),
                "the write tool ran despite being pruned from the pool");
        // ★ THE PIN IS THE INVARIANT, NOT THE WORDING ★ R410 changed the
        // refusal's WORDS on purpose: "unknown tool: file_write" said the
        // tool does not EXIST, so the model went looking for the right
        // name (48 calls in R408's S1). A pin on the old sentence would
        // have gone red on the improvement; a pin on the invariant (the
        // tool did not run, and the refusal NAMES it) survives it.
        boolean refused = events.stream().anyMatch(e ->
                e instanceof StreamEvent.ToolResult tr
                        && tr.isError()
                        && String.valueOf(tr.content()).contains("file_write"));
        assertTrue(refused,
                "the executor should refuse the pruned tool, naming the tool it refused; events: " + events);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aWritablePostureStillExecutesItsTools() {
        // The other direction: the prune must not break the normal
        // write path. A regression here would be "the agent can never
        // write anything any more".
        AtomicInteger writeCalls = new AtomicInteger();
        String prompt = "实现一个 LRU 缓存模块";
        IntentClassifier.Intent intent = IntentClassifier.classify(prompt);
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, intent.kind(), intent.reason());

        RecordingClient client = new RecordingClient("file_write");
        List<Tool> pool = List.of(
                tool("file_read", new AtomicInteger()),
                tool("file_write", writeCalls));
        QueryEngine engine = engineWith(client, pool);
        engine.query(prompt, null, ctxFor(intent, pool)).forEach(e -> {});

        assertEquals(1, writeCalls.get(), "the write tool should have run");
    }

    // ------------------------------------------------------------------
    // 4. the wire carries the verdict
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void theRunStartCarriesTheIntentAndTheProfile() {
        String prompt = "这个仓库的 checkpoint 机制是如何设计的？";
        IntentClassifier.Intent intent = IntentClassifier.classify(prompt);
        RecordingClient client = new RecordingClient(null);
        List<Tool> pool = List.of(tool("file_read", new AtomicInteger()));
        QueryEngine engine = engineWith(client, pool);
        List<StreamEvent> events = new ArrayList<>();
        engine.query(prompt, null, ctxFor(intent, pool)).forEach(events::add);

        StreamEvent.RunStart start = events.stream()
                .filter(e -> e instanceof StreamEvent.RunStart)
                .map(e -> (StreamEvent.RunStart) e)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no RunStart in " + events));
        assertEquals("answer", start.intent());
        assertEquals("仓库答疑", start.intentLabel());
        assertEquals("analyst", start.profile());
        assertEquals("读代码答疑", start.profileLabel());
    }
}
