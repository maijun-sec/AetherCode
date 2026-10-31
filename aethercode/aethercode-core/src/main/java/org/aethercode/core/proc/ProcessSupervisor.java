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

package org.aethercode.core.proc;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * a lightweight process supervisor. Tracks the lifecycle
 * of an external process (e.g. an MCP server or LSP daemon),
 * recording its state and exit code. The actual {@link Process}
 * spawning is left to the caller — this class is the
 * bookkeeping layer.
 */
public class ProcessSupervisor {

    public enum State { NEW, RUNNING, EXITED, FAILED, TERMINATED }

    public record Event(String processId, State newState, int exitCode, long timestampMs) {}

    public record ProcessInfo(
            String id,
            String command,
            State state,
            int exitCode,
            long startedAtMs,
            long endedAtMs
    ) {
        public long durationMs() {
            return endedAtMs > 0 ? endedAtMs - startedAtMs : 0;
        }
    }

    private final Map<String, ProcessInfo> processes = new LinkedHashMap<>();
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final AtomicLong processCounter = new AtomicLong();
    private final int eventLimit = 200;

    public synchronized ProcessInfo register(String id, String command) {
        Objects.requireNonNull(command, "command");
        String useId = id == null || id.isBlank() ? "proc-" + processCounter.incrementAndGet() : id;
        if (processes.containsKey(useId)) throw new IllegalStateException("process already exists: " + useId);
        long now = System.currentTimeMillis();
        ProcessInfo info = new ProcessInfo(useId, command, State.NEW, -1, now, 0);
        processes.put(useId, info);
        return info;
    }

    public synchronized void markRunning(String id) {
        update(id, p -> new ProcessInfo(p.id(), p.command(), State.RUNNING, -1, System.currentTimeMillis(), 0));
    }

    public synchronized void markExited(String id, int exitCode) {
        update(id, p -> new ProcessInfo(p.id(), p.command(), State.EXITED, exitCode, p.startedAtMs(), System.currentTimeMillis()));
    }

    public synchronized void markFailed(String id, int exitCode) {
        update(id, p -> new ProcessInfo(p.id(), p.command(), State.FAILED, exitCode, p.startedAtMs(), System.currentTimeMillis()));
    }

    public synchronized void markTerminated(String id) {
        update(id, p -> new ProcessInfo(p.id(), p.command(), State.TERMINATED, -1, p.startedAtMs(), System.currentTimeMillis()));
    }

    public Optional<ProcessInfo> info(String id) {
        return Optional.ofNullable(processes.get(id));
    }

    public List<ProcessInfo> all() { return List.copyOf(processes.values()); }

    public int size() { return processes.size(); }

    public State stateOf(String id) {
        ProcessInfo p = processes.get(id);
        return p == null ? null : p.state();
    }

    public List<Event> events() { return List.copyOf(events); }

    public int eventCount() { return events.size(); }

    public synchronized void forget(String id) {
        processes.remove(id);
    }

    public int runningCount() {
        int n = 0;
        for (ProcessInfo p : processes.values()) if (p.state() == State.RUNNING) n++;
        return n;
    }

    private void update(String id, java.util.function.Function<ProcessInfo, ProcessInfo> updater) {
        ProcessInfo cur = processes.get(id);
        if (cur == null) throw new IllegalArgumentException("unknown process: " + id);
        ProcessInfo next = updater.apply(cur);
        processes.put(id, next);
        events.add(new Event(id, next.state(), next.exitCode(), System.currentTimeMillis()));
        while (events.size() > eventLimit) events.remove(0);
    }
}
