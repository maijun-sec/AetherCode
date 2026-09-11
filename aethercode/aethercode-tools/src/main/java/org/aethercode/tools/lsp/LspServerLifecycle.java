package org.aethercode.tools.lsp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * a tiny lifecycle manager for LSP servers. Tracks state
 * (CREATED → STARTING → INITIALIZING → READY → STOPPED → FAILED)
 * and emits a {@link Event} on each transition. Concrete
 * transport (stdio / socket / ws) lives elsewhere; this class
 * is the state machine.
 */
public class LspServerLifecycle {

    public enum State { CREATED, STARTING, INITIALIZING, READY, STOPPED, FAILED }

    public record Event(String serverId, State from, State to, long timestampMs) {}

    public record ServerInfo(String id, String language, String command, State state) {}

    private final Map<String, ServerInfo> servers = new LinkedHashMap<>();
    private final Map<String, State> states = new LinkedHashMap<>();
    private final java.util.List<Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final int eventLimit = 100;
    private final AtomicLong transitionCount = new AtomicLong();

    public synchronized ServerInfo create(String id, String language, String command) {
        Objects.requireNonNull(id, "id");
        if (states.containsKey(id)) throw new IllegalStateException("server already exists: " + id);
        states.put(id, State.CREATED);
        ServerInfo info = new ServerInfo(id, language, command, State.CREATED);
        servers.put(id, info);
        emit(id, null, State.CREATED);
        return info;
    }

    public synchronized boolean transition(String id, State target) {
        State current = states.get(id);
        if (current == null) return false;
        if (!isValidTransition(current, target)) return false;
        states.put(id, target);
        ServerInfo info = servers.get(id);
        if (info != null) {
            servers.put(id, new ServerInfo(info.id(), info.language(), info.command(), target));
        }
        emit(id, current, target);
        return true;
    }

    public State stateOf(String id) {
        return states.get(id);
    }

    public boolean isReady(String id) {
        return states.get(id) == State.READY;
    }

    public boolean isStopped(String id) {
        State s = states.get(id);
        return s == State.STOPPED || s == State.FAILED;
    }

    public Optional<ServerInfo> info(String id) {
        return Optional.ofNullable(servers.get(id));
    }

    public java.util.List<ServerInfo> all() { return new java.util.ArrayList<>(servers.values()); }

    public java.util.List<Event> events() { return java.util.List.copyOf(events); }

    public long transitionCount() { return transitionCount.get(); }

    public int size() { return servers.size(); }

    public synchronized boolean remove(String id) {
        if (!states.containsKey(id)) return false;
        if (!isStopped(id)) return false;
        servers.remove(id);
        states.remove(id);
        return true;
    }

    private static boolean isValidTransition(State from, State to) {
        return switch (from) {
            case CREATED      -> to == State.STARTING || to == State.FAILED;
            case STARTING     -> to == State.INITIALIZING || to == State.FAILED;
            case INITIALIZING -> to == State.READY || to == State.FAILED;
            case READY        -> to == State.STOPPED || to == State.FAILED;
            case STOPPED      -> to == State.CREATED; // restart
            case FAILED       -> to == State.CREATED; // restart after error
        };
    }

    private void emit(String id, State from, State to) {
        events.add(new Event(id, from, to, System.currentTimeMillis()));
        transitionCount.incrementAndGet();
        while (events.size() > eventLimit) events.remove(0);
    }
}
