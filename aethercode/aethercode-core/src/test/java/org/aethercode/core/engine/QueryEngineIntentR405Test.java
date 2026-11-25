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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R405: the user-visible consequence of the imperative-position fix.
 *
 * <p>★ WHY THIS CLASS EXISTS, WHEN THE CLASSIFIER HAS ITS OWN TEST ★
 * The classifier test pins the VERDICT. This one pins what the user
 * actually gets: whether {@code file_write} is in the tool table the
 * MODEL is shown, and whether the EXECUTOR can resolve it.
 *
 * <p>That gap is not academic — it is exactly where R401's promise was
 * broken and where R404's fix lived. Before R405, "Update pom.xml"
 * classified as UNCERTAIN ⇒ the read-only {@code navigator} profile ⇒
 * {@code file_write} pruned ⇒ the model could not write the file it was
 * asked to write.
 *
 * <p>★ THE DECISIVE VARIABLE ★
 * The two prompts below contain the SAME word ("update") and differ only
 * in WHERE it sits: one opens the sentence, the other sits inside a
 * noun phrase. If the position were not the cause, the two would behave
 * the same way — and then the classifier's rule would not be the thing
 * under test.
 */
class QueryEngineIntentR405Test {

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
        // WHOLE verdict (domain + capability), not from the kind alone.
        Profile p = ProfileRegistry.forIntent(intent);
        // The SAME tool instances the test counts on must be the ones
        // the executor resolves — otherwise the test counts a
        // different object than the one that ran.
        return new RunContext(intent, p, ProfileRegistry.prune(pool, p));
    }

    // ------------------------------------------------------------------
    // 1. the write tool reaches the model
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void theWriteToolReachesTheModelForABareVerbRequest() {
        // ★ THE USER-VISIBLE OUTCOME ★
        AtomicInteger writeCalls = new AtomicInteger();
        String prompt = "Update pom.xml";
        IntentClassifier.Intent intent = IntentClassifier.classify(prompt);
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, intent.kind(), intent.reason());

        RecordingClient client = new RecordingClient("file_write");
        List<Tool> pool = List.of(
                tool("file_read", new AtomicInteger()),
                tool("file_write", writeCalls));
        QueryEngine engine = engineWith(client, pool);
        engine.query(prompt, null, ctxFor(intent, pool)).forEach(e -> {});

        assertTrue(toolNames(client.tools).contains("file_write"),
                "the model was not shown file_write: " + toolNames(client.tools));
        assertEquals(1, writeCalls.get(),
                "the write tool did not run — the model could have asked for it and been refused");
    }

    // ------------------------------------------------------------------
    // 2. the control — the noun phrase is still read-only
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aNounPhraseIsStillReadOnly() {
        // The other direction. "give me a status update on the migration"
        // names the same word, and it is NOT an instruction — so the
        // write tools must NOT be handed over.
        AtomicInteger writeCalls = new AtomicInteger();
        String prompt = "give me a status update on the migration";
        IntentClassifier.Intent intent = IntentClassifier.classify(prompt);
        assertEquals(IntentClassifier.Kind.UNCERTAIN, intent.kind(), intent.reason());

        RecordingClient client = new RecordingClient("file_write");
        List<Tool> pool = List.of(
                tool("file_read", new AtomicInteger()),
                tool("file_write", writeCalls));
        QueryEngine engine = engineWith(client, pool);
        engine.query(prompt, null, ctxFor(intent, pool)).forEach(e -> {});

        assertFalse(toolNames(client.tools).contains("file_write"),
                "a noun phrase was given the write tools: " + toolNames(client.tools));
        assertEquals(0, writeCalls.get(),
                "the write tool ran despite a read-only posture");
    }

    // ------------------------------------------------------------------
    // 3. the POSITION is the only variable
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void thePositionIsTheOnlyVariable() {
        // Two prompts carrying the SAME word, differing only in where
        // the word sits. If they landed on the same posture, the
        // position would not be the thing under test — and the whole
        // fix would be a coincidence.
        IntentClassifier.Intent request = IntentClassifier.classify("Update pom.xml");
        IntentClassifier.Intent nounPhrase = IntentClassifier.classify("give me a status update");
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, request.kind(), request.reason());
        assertEquals(IntentClassifier.Kind.UNCERTAIN, nounPhrase.kind(), nounPhrase.reason());
    }
}
