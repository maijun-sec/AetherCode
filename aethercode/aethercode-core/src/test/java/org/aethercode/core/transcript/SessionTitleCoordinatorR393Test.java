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

package org.aethercode.core.transcript;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R393: WHEN a session gets its title.
 *
 * <p>The coordinator is the part that decides whether to spend a model
 * call. It is called on every session-list refresh, so the guards are
 * the feature:
 * <ul>
 *   <li>a session that already has a title is never asked about;</li>
 *   <li>a session with no prompt is never asked about;</li>
 *   <li>a session is asked at most once per {@link
 *       SessionTitleCoordinator#RETRY_COOLDOWN_MILLIS} — so a broken
 *       provider cannot be hammered;</li>
 *   <li>the work happens in the BACKGROUND — the list must not wait
 *       for the model.</li>
 * </ul>
 */
class SessionTitleCoordinatorR393Test {

    /** Counts the calls and answers with a fixed title. */
    private static final class CountingClient implements ChatClient {
        final AtomicInteger calls = new AtomicInteger();
        private final String answer;

        CountingClient(String answer) {
            this.answer = answer;
        }

        @Override
        public Stream<StreamEvent> stream(List<Message> messages,
                                           String systemPrompt,
                                           List<Tool> tools) {
            calls.incrementAndGet();
            return Stream.of(
                    new StreamEvent.TextDelta(answer),
                    new StreamEvent.RunEnd("end_turn", List.of()));
        }

        @Override
        public String modelId() {
            return "stub";
        }
    }

    private static SessionStore storeIn(Path cwd) {
        return new SessionStore(cwd.resolve(".aethercode/sessions"));
    }

    @Test
    void aSessionWithATitleIsNeverAskedAbout(@TempDir Path cwd) throws Exception {
        SessionStore store = storeIn(cwd);
        store.touch("s1", cwd.toString(), null, null, "做一个排序项目", "我的项目", 0);
        CountingClient client = new CountingClient("实现排序算法");
        SessionTitleCoordinator c = new SessionTitleCoordinator(
                () -> store, sid -> client, ev -> {});
        c.maybeSummarize("s1", "做一个排序项目", true);
        Thread.sleep(150);
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void aSessionWithoutAPromptIsNeverAskedAbout(@TempDir Path cwd) throws Exception {
        SessionStore store = storeIn(cwd);
        CountingClient client = new CountingClient("实现排序算法");
        SessionTitleCoordinator c = new SessionTitleCoordinator(
                () -> store, sid -> client, ev -> {});
        c.maybeSummarize("s1", null, false);
        c.maybeSummarize("s1", "   ", false);
        Thread.sleep(150);
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void theTitleLandsAndTheListenerIsTold(@TempDir Path cwd) throws Exception {
        SessionStore store = storeIn(cwd);
        store.touch("s1", cwd.toString(), null, null, "做一个排序项目", null, 0);
        CountingClient client = new CountingClient("实现排序算法");
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder seen = new StringBuilder();
        SessionTitleCoordinator c = new SessionTitleCoordinator(
                () -> store, sid -> client, ev -> {
                    seen.append(ev.sessionId()).append("=").append(ev.title());
                    latch.countDown();
                });
        c.maybeSummarize("s1", "做一个排序项目", false);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.toString()).isEqualTo("s1=实现排序算法");
        // and it is PERSISTED — the next list serves it without a call
        assertThat(store.list().stream().filter(x -> x.id().equals("s1")).findFirst()
                .orElseThrow().title()).isEqualTo("实现排序算法");
    }

    @Test
    void theSecondCallWithinTheCooldownIsNotAsked(@TempDir Path cwd) throws Exception {
        SessionStore store = storeIn(cwd);
        store.touch("s1", cwd.toString(), null, null, "做一个排序项目", null, 0);
        CountingClient client = new CountingClient("实现排序算法");
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "title-test");
            t.setDaemon(true);
            return t;
        });
        SessionTitleCoordinator c = new SessionTitleCoordinator(
                () -> store, sid -> client, ev -> {}, ex);
        try {
            c.maybeSummarize("s1", "做一个排序项目", false);
            Thread.sleep(200);
            c.maybeSummarize("s1", "做一个排序项目", false);
            Thread.sleep(200);
            // ★ THE GUARD ★ listSessions is called on every refresh; the
            // model must be asked ONCE, not once per refresh.
            assertThat(client.calls.get()).isEqualTo(1);
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void aMissingChatClientCostsNothing(@TempDir Path cwd) throws Exception {
        SessionStore store = storeIn(cwd);
        store.touch("s1", cwd.toString(), null, null, "做一个排序项目", null, 0);
        SessionTitleCoordinator c = new SessionTitleCoordinator(
                () -> store, sid -> null, ev -> {});
        c.maybeSummarize("s1", "做一个排序项目", false);
        Thread.sleep(150);
        // no title, no crash
        assertThat(store.list().stream().filter(x -> x.id().equals("s1")).findFirst()
                .orElseThrow().title()).isNull();
    }

    @Test
    void theUsersRenameSurvivesTheModel(@TempDir Path cwd) throws Exception {
        SessionStore store = storeIn(cwd);
        store.touch("s1", cwd.toString(), null, null, "做一个排序项目", null, 0);
        store.rename("s1", "我的排序项目");
        CountingClient client = new CountingClient("实现排序算法");
        CountDownLatch latch = new CountDownLatch(1);
        SessionTitleCoordinator c = new SessionTitleCoordinator(
                () -> store, sid -> client, ev -> latch.countDown());
        c.maybeSummarize("s1", "做一个排序项目", false);
        Thread.sleep(300);
        // ★ the model's title is dropped — the user's name wins
        assertThat(store.list().stream().filter(x -> x.id().equals("s1")).findFirst()
                .orElseThrow().title()).isEqualTo("我的排序项目");
        assertThat(latch.getCount()).isEqualTo(1L);   // no notification either
    }
}
