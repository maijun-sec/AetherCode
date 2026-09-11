package org.aethercode.bridge.backends;

import org.aethercode.bridge.SwarmCoordinator;

/**
 * pluggable teammate backend. Modelled on the TS
 * {@code src/utils/swarm/backends/registry.ts} — the registry maps a backend name
 * to an implementation that knows how to spawn a teammate and ship tasks to it.
 *
 * <p>prior round ships:
 * <ul>
 *   <li>{@code in-process} — already in {@link SwarmCoordinator} (prior round)</li>
 *   <li>{@code tmux} — spawn a tmux window per teammate</li>
 *   <li>{@code iterm2} — same idea, iTerm2 escape sequences</li>
 * </ul>
 */
public interface TeammateBackend {

    String name();

    /**
     * Spawn a teammate. Returns an opaque id; the bridge uses it to send tasks and to
     * recognise the teammate's reply stream.
     */
    String spawn(SwarmCoordinator.TeammateSpec spec) throws Exception;

    /** Send a task; the future completes when the teammate replies. */
    java.util.concurrent.CompletableFuture<String> sendTask(String teammateId, String task);

    /** Tear down. */
    void retire(String teammateId);
}
