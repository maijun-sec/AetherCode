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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * R393: decide WHEN a session gets its model-written title, and do it in
 * the background.
 *
 * <p>★ WHY A COORDINATOR AND NOT A CALL IN THE READ PATH ★ The session
 * title is a DERIVED fact: it can be computed from the session's first
 * prompt at any time. So the trigger is "somebody looked at the session
 * list and the title is still missing" — a lazy materialisation. Two
 * things have to be true for that to be safe:
 * <ol>
 *   <li><b>It must not fire on every call.</b> {@code listSessions} is
 *       called on every refresh. The in-memory attempt map plus the
 *       cooldown make the cost "one attempt per session per
 *       {@link #RETRY_COOLDOWN_MILLIS}".</li>
 *   <li><b>It must not block the caller.</b> The model call takes
 *       seconds; the list must not wait for it. So the work happens on a
 *       background thread and the caller returns immediately with the
 *       old (long) title; the listener is told when the short one
 *       lands.</li>
 * </ol>
 *
 * <p>★ WHY A COOLDOWN AT ALL ★ When the provider is down (no API key,
 * a 500), every attempt fails. Without a cooldown the daemon would
 * hammer a broken endpoint — once per list refresh, per session. With
 * it, a broken provider costs one failed call per session per ten
 * minutes.
 *
 * <p>★ WHY THE THREADS ARE DAEMONS ★ A non-daemon pool keeps the JVM
 * alive, so the daemon would refuse to exit. Daemon threads die with
 * the process.
 */
public final class SessionTitleCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(SessionTitleCoordinator.class);

    /** How long to wait before trying the same session again. */
    public static final long RETRY_COOLDOWN_MILLIS = 10 * 60_000L;

    /** How many title calls may be in flight at once. */
    public static final int WORKERS = 2;

    private final Supplier<SessionStore> storeSupplier;
    private final Function<String, ChatClient> clientResolver;
    private final Consumer<TitleEvent> listener;
    private final ExecutorService executor;
    private final Map<String, Long> lastAttempt = new ConcurrentHashMap<>();

    /** What the listener is told when a title lands. */
    public record TitleEvent(String sessionId, String title) {}

    /**
     * @param storeSupplier resolves the session index. It is a SUPPLIER
     *                       and not a value because the daemon installs
     *                       the store AFTER the methods object is
     *                       built — a value captured at construction
     *                       time would be null forever.
     * @param clientResolver resolves the chat client for a session id
     *                       (the session's own engine, or the daemon's
     *                       primary one when that session is not loaded)
     */
    public SessionTitleCoordinator(Supplier<SessionStore> storeSupplier,
                                   Function<String, ChatClient> clientResolver,
                                   Consumer<TitleEvent> listener) {
        this(storeSupplier, clientResolver, listener, defaultExecutor());
    }

    public SessionTitleCoordinator(Supplier<SessionStore> storeSupplier,
                                   Function<String, ChatClient> clientResolver,
                                   Consumer<TitleEvent> listener,
                                   ExecutorService executor) {
        this.storeSupplier = storeSupplier;
        this.clientResolver = clientResolver;
        this.listener = listener;
        this.executor = executor;
    }

    private static ExecutorService defaultExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "aethercode-title-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(WORKERS, tf);
    }

    /**
     * Ask for a title for this session — if it needs one.
     *
     * <p>Safe to call on every list refresh: the attempt map and the
     * cooldown make it a no-op after the first try.
     *
     * @param sessionId   the session
     * @param firstPrompt the prompt the session started from
     * @param hasTitle    whether the session ALREADY has a title (the
     *                    user's rename, or a title we wrote before) —
     *                    when true this is a no-op
     */
    public void maybeSummarize(String sessionId, String firstPrompt, boolean hasTitle) {
        if (hasTitle) return;
        if (sessionId == null || sessionId.isBlank()) return;
        if (firstPrompt == null || firstPrompt.isBlank()) return;
        if (storeSupplier == null) return;
        long now = System.currentTimeMillis();
        Long last = lastAttempt.get(sessionId);
        if (last != null && now - last < RETRY_COOLDOWN_MILLIS) return;
        lastAttempt.put(sessionId, now);
        ChatClient client;
        try {
            client = clientResolver == null ? null : clientResolver.apply(sessionId);
        } catch (RuntimeException e) {
            client = null;
        }
        if (client == null) {
            // No provider wired — nothing to ask. The attempt is still
            // recorded, so we do not re-check on every refresh.
            LOG.debug("R393: no chat client for the title of {}", sessionId);
            return;
        }
        final ChatClient chat = client;
        try {
            executor.submit(() -> run(sessionId, firstPrompt, chat));
        } catch (RuntimeException e) {
            // A rejected task (the pool is shut down) must not break the
            // list.
            LOG.debug("R393: could not schedule the title of {}: {}", sessionId, e.getMessage());
        }
    }

    private void run(String sessionId, String firstPrompt, ChatClient client) {
        try {
            SessionStore store = storeSupplier.get();
            if (store == null) {
                LOG.debug("R393: no session store for the title of {}", sessionId);
                return;
            }
            SessionTitleSummarizer summarizer = new SessionTitleSummarizer(client, store);
            String title = summarizer.summarize(firstPrompt);
            if (title == null) {
                LOG.debug("R393: no usable title for {}", sessionId);
                return;
            }
            // ★ The write is "only if empty" — the user's own rename wins.
            boolean written = summarizer.applyTitle(sessionId, title);
            if (!written) {
                LOG.debug("R393: {} already had a title — the model's was dropped", sessionId);
                return;
            }
            LOG.info("R393: titled session {} as \"{}\"", sessionId, title);
            if (listener != null) {
                try {
                    listener.accept(new TitleEvent(sessionId, title));
                } catch (RuntimeException e) {
                    LOG.debug("R393: title listener failed for {}: {}", sessionId, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("R393: title generation for {} failed: {}", sessionId, e.getMessage());
        }
    }

    /** Forget the attempt history — used by tests. */
    public void resetAttempts() {
        lastAttempt.clear();
    }

    /** The executor, so a caller can shut it down. */
    public ExecutorService executor() {
        return executor;
    }
}
