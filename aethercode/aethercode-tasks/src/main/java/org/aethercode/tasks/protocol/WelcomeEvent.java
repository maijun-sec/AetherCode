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

package org.aethercode.tasks.protocol;

import org.aethercode.tasks.supervisor.ChildRecord;
import org.aethercode.tasks.supervisor.ChildStatus;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * R-P1-T21 (app-spec/tasks.md §1.2): the {@code welcome}
 * payload the supervisor sends over the socket immediately
 * after a client connects. The client uses it to back-fill
 * its session list without issuing a {@code session/list}
 * poll.
 *
 * <p>The welcome payload contains:
 * <ul>
 *   <li>{@code serverVersion} — the supervisor's version string;</li>
 *   <li>{@code activeTasks} — a list of every child currently
 *       in a non-terminal state ({@code QUEUED},
 *       {@code RUNNING}, {@code PAUSED}) so the client can
 *       decide which session(s) to re-attach to;</li>
 *   <li>{@code activeTaskCount} — for the header / status
 *       bar without forcing a count of the list;</li>
 *   <li>{@code emittedAtMs} — when the welcome was assembled
 *       (for clock skew debugging).</li>
 * </ul>
 *
 * <p>The wire shape is a {@link Map} so callers can pass it
 * through {@link org.aethercode.tasks.supervisor.SupervisorRpcServer}
 * without an extra Jackson pass.
 */
public final class WelcomeEvent {

    private static final Logger LOG = LoggerFactory.getLogger(WelcomeEvent.class);

    /** Wire-protocol name of the welcome event. */
    public static final String EVENT_NAME = "welcome";

    private final String serverVersion;
    private final long emittedAtMs;
    private final List<Map<String, Object>> activeTasks;

    public WelcomeEvent(String serverVersion, long emittedAtMs,
                        List<Map<String, Object>> activeTasks) {
        this.serverVersion = Objects.requireNonNull(serverVersion, "serverVersion");
        this.emittedAtMs = emittedAtMs;
        this.activeTasks = List.copyOf(activeTasks);
    }

    public String serverVersion() { return serverVersion; }
    public long emittedAtMs() { return emittedAtMs; }
    public List<Map<String, Object>> activeTasks() { return activeTasks; }
    public int activeTaskCount() { return activeTasks.size(); }

    /**
     * Build the wire payload. The result is a fresh
     * {@link LinkedHashMap} and is safe to mutate (the caller
     * is expected to serialise and ship it).
     */
    public Map<String, Object> toPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event", EVENT_NAME);
        out.put("serverVersion", serverVersion);
        out.put("emittedAtMs", emittedAtMs);
        out.put("activeTaskCount", activeTaskCount());
        out.put("activeTasks", new ArrayList<>(activeTasks));
        return out;
    }

    /**
     * Build a {@code WelcomeEvent} from the supervisor's
     * current view of active children. "Active" means any
     * non-terminal status (the same set the supervisor uses
     * to decide which children to resume on startup).
     */
    public static WelcomeEvent fromStore(SupervisorStore store, String serverVersion)
            throws SQLException {
        Objects.requireNonNull(store, "store");
        List<ChildRecord> resumable = store.listResumable();
        List<Map<String, Object>> tasks = new ArrayList<>(resumable.size());
        for (ChildRecord r : resumable) {
            tasks.add(toActiveTask(r));
        }
        return new WelcomeEvent(serverVersion, System.currentTimeMillis(), tasks);
    }

    /**
     * Single-task entry. The field names match the design
     * doc's welcome payload:
     * {@code id, title, state, last_active_at}.
     */
    public static Map<String, Object> toActiveTask(ChildRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("title", titleFor(r));
        m.put("state", r.status().name());
        // last_active_at: prefer started_at, fall back to
        // created_at, then null.
        Long lastActive = r.startedAtMs() != null ? r.startedAtMs() : r.createdAtMs();
        m.put("last_active_at", lastActive);
        m.put("cwd", r.cwd());
        return m;
    }

    /** Best-effort title; falls back to a one-line preview of the prompt. */
    public static String titleFor(ChildRecord r) {
        String prompt = r.prompt();
        if (prompt == null || prompt.isBlank()) return "Untitled task";
        String flat = prompt.replace('\n', ' ').trim();
        if (flat.length() <= 60) return flat;
        return flat.substring(0, 57) + "...";
    }

    @Override
    public String toString() {
        return "WelcomeEvent{" +
                "serverVersion='" + serverVersion + '\'' +
                ", activeTaskCount=" + activeTaskCount() +
                ", emittedAtMs=" + emittedAtMs +
                '}';
    }

    // -- helpers ----------------------------------------------------------

    /** True if a status is "active" (non-terminal). */
    public static boolean isActive(ChildStatus s) {
        return s == ChildStatus.QUEUED || s == ChildStatus.RUNNING || s == ChildStatus.PAUSED;
    }
}
