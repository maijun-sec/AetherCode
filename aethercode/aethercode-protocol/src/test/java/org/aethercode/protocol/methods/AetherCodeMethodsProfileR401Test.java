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

package org.aethercode.protocol.methods;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R401 — the profile override surface.
 *
 * <h2>★ WHAT THIS GATE HOLDS ★</h2>
 *
 * <ul>
 *   <li><b>The override REACHES the engine.</b> A classifier that says
 *       CODE_CHANGE and a user who says "no, this is a question" must
 *       end up with the QUESTION's posture. If the override only
 *       changed a field nobody reads, the UI's "一键改" button would be
 *       a lie.</li>
 *   <li><b>An unknown profile name is REJECTED, not ignored.</b> A typo
 *       that silently fell back to "auto" would look like the override
 *       did not take — and the user would be left with a mis-classified
 *       turn and no way to tell why.</li>
 *   <li><b>The RPC is on BOTH transports.</b> {@code registerAll} covers
 *       the stdio daemon; {@code HttpJsonRpcServer}'s hand-rolled switch
 *       covers the HTTP+WS daemon the desktop actually talks to. A
 *       method on only one of them is invisible to half the clients —
 *       that is the Lesson 1022 shape.</li>
 *   <li><b>The verdict is ON THE WIRE.</b> The renderer must not
 *       re-derive what the agent thought the turn was about (the R385
 *       rule: the verdict comes from the daemon).</li>
 * </ul>
 */
class AetherCodeMethodsProfileR401Test {

    /** a chat client that records what the model was shown. */
    private static final class RecordingClient implements ChatClient {
        final AtomicReference<String> systemPrompt = new AtomicReference<>();
        final AtomicReference<List<String>> toolNames = new AtomicReference<>();
        @Override
        public Stream<StreamEvent> stream(List<Message> m, String sp, List<Tool> tools) {
            systemPrompt.set(sp);
            java.util.List<String> names = new java.util.ArrayList<>();
            for (Tool t : tools) names.add(t.name());
            toolNames.set(names);
            return Stream.of(
                    new StreamEvent.RunStart("r1", "stub"),
                    new StreamEvent.TextDelta("ok"),
                    new StreamEvent.RunEnd("end_turn", List.of()));
        }
        @Override public String modelId() { return "stub"; }
    }

    private static final class Rig {
        final AetherCodeEngine engine;
        final AetherCodeMethods methods;
        final RecordingClient chat = new RecordingClient();
        final List<JsonRpcNotification> notifications = new CopyOnWriteArrayList<>();

        Rig(Path cwd) {
            engine = new AetherCodeEngine.Builder()
                    .cwd(cwd)
                    .tools(List.of(stubTool("file_read"), stubTool("file_write")))
                    .build();
            engine.setChatClient(chat);
            methods = new AetherCodeMethods(engine, notifications::add);
        }
    }

    private static Tool stubTool(String name) {
        return org.aethercode.core.tool.Tools.build(new org.aethercode.core.tool.ToolDef(
                name, name, Map.of("type", "object", "properties", Map.of()),
                (input, ctx) -> java.util.concurrent.CompletableFuture.completedFuture(
                        Tool.ToolResult.of("ok"))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    // ------------------------------------------------------------------
    // the RPC itself
    // ------------------------------------------------------------------

    @Test
    void settingAValidProfileStoresTheOverride(@TempDir Path cwd) {
        Rig rig = new Rig(cwd);
        Map<String, Object> r = asMap(rig.methods.setProfile(Map.of("profile", "researcher")));
        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("profile", "researcher");
        assertThat(rig.engine.appState().profileOverride()).isEqualTo("researcher");
    }

    @Test
    void autoClearsTheOverride(@TempDir Path cwd) {
        Rig rig = new Rig(cwd);
        rig.methods.setProfile(Map.of("profile", "researcher"));
        Map<String, Object> r = asMap(rig.methods.setProfile(Map.of("profile", "auto")));
        assertThat(r).containsEntry("ok", true);
        // null = "auto" — AssertJ's containsEntry(K, V) is ambiguous
        // with a null value (it reads as the varargs form), so read
        // the entry directly.
        assertThat(r.get("profile")).isNull();
        assertThat(rig.engine.appState().profileOverride()).isNull();
    }

    @Test
    void anUnknownProfileIsRejectedAndTheOverrideIsLeftAlone(@TempDir Path cwd) {
        Rig rig = new Rig(cwd);
        rig.methods.setProfile(Map.of("profile", "researcher"));
        Map<String, Object> r = asMap(rig.methods.setProfile(Map.of("profile", "reseacher")));
        assertThat(r).containsEntry("ok", false);
        assertThat((String) r.get("error")).contains("reseacher");
        // the valid names travel with the error so a renderer never
        // has to hard-code them.
        assertThat(((List<?>) r.get("profiles")).size()).isEqualTo(7);
        assertThat(((List<?>) r.get("profiles")).get(0).toString()).contains("builder");
        // ★ the previous override must SURVIVE the rejected call.
        assertThat(rig.engine.appState().profileOverride()).isEqualTo("researcher");
    }

    @Test
    void theResponseCarriesTheProfileNames(@TempDir Path cwd) {
        Rig rig = new Rig(cwd);
        Map<String, Object> r = asMap(rig.methods.setProfile(Map.of("profile", "auto")));
        // the wire carries {name, label, description} per profile — the
        // renderer renders the LABEL and sends the NAME back, so it
        // never needs its own copy of the vocabulary.
        List<?> profiles = (List<?>) r.get("profiles");
        assertThat(profiles).hasSize(7);
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Object o : profiles) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            names.add((String) m.get("name"));
            assertThat((String) m.get("label")).isNotBlank();
        }
        assertThat(names).containsExactly("builder", "debugger", "researcher", "analyst",
                "reviewer", "operator", "navigator");
    }

    // ------------------------------------------------------------------
    // the override reaches the engine
    // ------------------------------------------------------------------

    @Test
    void theOverrideReachesTheEngineAndBeatsTheClassifier(@TempDir Path cwd) throws Exception {
        Rig rig = new Rig(cwd);
        // the classifier would say CODE_CHANGE for this prompt...
        // "实现一个 LRU 缓存" really is a code request, but the USER
        // says "no, treat it as research".
        rig.methods.setProfile(Map.of("profile", "researcher"));

        @SuppressWarnings("unchecked")
        Map<String, Object> accepted =
                (Map<String, Object>) rig.methods.query(Map.of("prompt", "实现一个 LRU 缓存"));
        assertThat(accepted).containsEntry("accepted", true);
        awaitRunStart(rig);

        // ...and the posture directive the model was shown is the
        // RESEARCH one, not the builder one.
        String sys = rig.chat.systemPrompt.get();
        assertThat(sys).isNotNull();
        assertThat(sys).contains("RESEARCH / concept lookup");
        assertThat(sys).doesNotContain("This turn is a **CODE CHANGE**");
        // ★ AND the override PRUNES the tools — a directive alone is
        // soft. The model must not even SEE file_write.
        assertThat(rig.chat.toolNames.get()).doesNotContain("file_write");
        assertThat(rig.chat.toolNames.get()).contains("file_read");
    }

    @Test
    void withoutAnOverrideTheClassifiersVerdictIsUsed(@TempDir Path cwd) throws Exception {
        Rig rig = new Rig(cwd);
        @SuppressWarnings("unchecked")
        Map<String, Object> accepted =
                (Map<String, Object>) rig.methods.query(Map.of("prompt", "实现一个 LRU 缓存"));
        assertThat(accepted).containsEntry("accepted", true);
        awaitRunStart(rig);
        String sys = rig.chat.systemPrompt.get();
        assertThat(sys).isNotNull();
        assertThat(sys).contains("This turn is a **CODE CHANGE**");
    }

    // ------------------------------------------------------------------
    // the wire
    // ------------------------------------------------------------------

    @Test
    void theRunStartOnTheWireCarriesTheIntentAndProfile(@TempDir Path cwd) throws Exception {
        Rig rig = new Rig(cwd);
        @SuppressWarnings("unchecked")
        Map<String, Object> accepted =
                (Map<String, Object>) rig.methods.query(Map.of("prompt", "实现一个 LRU 缓存"));
        assertThat(accepted).containsEntry("accepted", true);

        Map<String, Object> runStart = awaitRunStart(rig);
        assertThat(runStart).containsEntry("intent", "code_change");
        assertThat(runStart).containsEntry("intentLabel", "代码生成");
        assertThat(runStart).containsEntry("profile", "builder");
        assertThat(runStart).containsEntry("profileLabel", "写代码");
    }

    // ------------------------------------------------------------------
    // both transports
    // ------------------------------------------------------------------

    @Test
    void theRpcIsRegisteredOnTheStdioTransport(@TempDir Path cwd) {
        Rig rig = new Rig(cwd);
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(m -> {});
        try {
            rig.methods.registerAll(dispatcher);
            assertThat(dispatcher.hasMethod("setProfile"))
                    .as("setProfile must be registered in registerAll() — the stdio daemon "
                            + "dispatches through that registry")
                    .isTrue();
        } finally {
            dispatcher.shutdown();
        }
    }

    @Test
    void theRpcIsInTheHttpDispatchSwitch() throws Exception {
        // The HTTP+WS daemon (the one the desktop talks to) dispatches
        // through a HAND-ROLLED switch, not through registerAll(). A
        // method registered in only one of the two surfaces is
        // invisible to half the clients — Lesson 1022's exact shape
        // (the memory/* gap).
        Path src = Path.of("src", "main", "java", "org", "aethercode", "protocol",
                "http", "HttpJsonRpcServer.java");
        assertThat(src).exists();
        String text = java.nio.file.Files.readString(src);
        assertThat(text)
                .as("the HTTP dispatch switch must carry setProfile, or the desktop "
                        + "gets METHOD_NOT_FOUND for it")
                .contains("case \"setProfile\"");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** wait for the run_start frame and return it. */
    private static Map<String, Object> awaitRunStart(Rig rig) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            for (JsonRpcNotification n : rig.notifications) {
                Object params = n.params();
                if (!(params instanceof Map)) continue;
                Object ev = ((Map<?, ?>) params).get("event");
                if (!(ev instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) ev;
                if ("run_start".equals(m.get("type"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> copy = new java.util.LinkedHashMap<>((Map<String, Object>) m);
                    return copy;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no run_start frame arrived; notifications: " + rig.notifications);
    }
}
