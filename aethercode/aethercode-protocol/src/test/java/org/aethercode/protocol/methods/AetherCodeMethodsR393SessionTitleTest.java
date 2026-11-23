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

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.transcript.SessionStore;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R393: the session list asks the model for a short title.
 *
 * <p>★ WHY THIS TEST IS AT THE PROTOCOL LAYER ★ The trigger lives in
 * {@code listSessions}: it is the one place that knows "this session has
 * no title" AND "this is its prompt". The unit tests of the core
 * classes prove the pieces; this one proves the WIRING — that listing
 * the sessions actually produces a title, that it is persisted, and
 * that the renderer is told.
 */
class AetherCodeMethodsR393SessionTitleTest {

    /** A ChatClient that answers with a fixed title. */
    private static final class StubClient implements ChatClient {
        @Override
        public Stream<StreamEvent> stream(List<Message> messages,
                                           String systemPrompt,
                                           List<Tool> tools) {
            return Stream.of(
                    new StreamEvent.TextDelta("实现排序算法"),
                    new StreamEvent.RunEnd("end_turn", List.of()));
        }

        @Override
        public String modelId() {
            return "stub";
        }
    }

    private static AetherCodeEngine engineFor(Path cwd) {
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        engine.setChatClient(new StubClient());
        return engine;
    }

    @Test
    void listingASessionGivesItAModelWrittenTitle(@TempDir Path cwd) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        store.touch("s1", cwd.toString(), null, null,
                "在当前项目目录下，创建一个 java maven 项目，支持 5 种排序实现", null, 0);

        List<JsonRpcNotification> notifications = new CopyOnWriteArrayList<>();
        CountDownLatch notified = new CountDownLatch(1);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {
            notifications.add(n);
            if ("session_title".equals(n.method())) notified.countDown();
        });
        // R393: the feature is OFF by default — the daemon switches it
        // on, because a default-built engine carries a chat client and
        // a unit test must not fire a real model call.
        m.setSessionTitleSummariesEnabled(true);

        m.listSessions(Map.of("withPreview", true));

        // the model call is on a BACKGROUND thread — wait for it
        assertThat(notified.await(15, TimeUnit.SECONDS)).isTrue();

        assertThat(store.list().stream().filter(x -> x.id().equals("s1")).findFirst()
                .orElseThrow().title()).isEqualTo("实现排序算法");

        JsonRpcNotification n = notifications.stream()
                .filter(x -> "session_title".equals(x.method()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) n.params();
        assertThat(params).containsEntry("sessionId", "s1");
        assertThat(params).containsEntry("title", "实现排序算法");
    }

    @Test
    void theUsersRenameIsNeverReplacedByTheModelsTitle(@TempDir Path cwd) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        store.touch("s1", cwd.toString(), null, null, "做一个排序项目", null, 0);
        store.rename("s1", "我的排序项目");

        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});
        m.setSessionTitleSummariesEnabled(true);
        m.listSessions(Map.of("withPreview", true));
        Thread.sleep(600);

        assertThat(store.list().stream().filter(x -> x.id().equals("s1")).findFirst()
                .orElseThrow().title()).isEqualTo("我的排序项目");
    }

    @Test
    void theFeatureIsOffUnlessTheDaemonSwitchesItOn(@TempDir Path cwd) throws Exception {
        // ★ THE SAFETY PROPERTY ★ A default-built engine DOES carry a
        // SpringAiChatClient. With the feature on by default, every
        // unit test that lists sessions would fire a REAL model call
        // with the test's prompt text — and on a machine that has an
        // API key set, that call would succeed and overwrite the title
        // the test is asserting on.
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        store.touch("s1", cwd.toString(), null, null, "做一个排序项目", null, 0);

        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});
        assertThat(m.sessionTitleSummariesEnabled()).isFalse();
        m.listSessions(Map.of("withPreview", true));
        Thread.sleep(500);

        // no title was written — the model was never asked
        assertThat(store.list().stream().filter(x -> x.id().equals("s1")).findFirst()
                .orElseThrow().title()).isNull();
    }

    @Test
    void theTitleIsStillServedWhileTheModelIsThinking(@TempDir Path cwd) throws Exception {
        // ★ THE LATENCY CONTRACT ★ the list must NOT wait for the
        // model. The first call answers with the long prompt (the model
        // has not answered yet); the short title arrives later, by
        // notification.
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        store.touch("s1", cwd.toString(), null, null, "做一个排序项目", null, 0);

        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});
        m.setSessionTitleSummariesEnabled(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(Map.of("withPreview", true));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("sessions");
        // the row is served immediately, with whatever title is known
        assertThat(rows.get(0).get("title")).isEqualTo("做一个排序项目");

        // ★ WAIT FOR THE BACKGROUND WRITE ★ the model call outlives
        // this request (that is the point), and it holds the SQLite
        // file open while it writes — so the test must let it finish
        // before JUnit deletes the temp directory.
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String t = store.list().stream().filter(x -> x.id().equals("s1"))
                    .findFirst().orElseThrow().title();
            if (t != null && !t.isBlank()) break;
            Thread.sleep(50);
        }
    }
}
