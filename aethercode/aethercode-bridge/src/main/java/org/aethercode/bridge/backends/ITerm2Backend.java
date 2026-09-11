package org.aethercode.bridge.backends;

import org.aethercode.bridge.SwarmCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * iTerm2-backend swarm. Each teammate gets its own iTerm2 session via the
 * iTerm2 proprietary escape sequence ({@code OSC 1337 ; StealFocus = ...}). The
 * bridge uses the same per-teammate output file pattern as {@link TmuxBackend}.
 */
public class ITerm2Backend implements TeammateBackend {

    private static final Logger LOG = LoggerFactory.getLogger(ITerm2Backend.class);

    private final Map<String, String> outputFileFor = new ConcurrentHashMap<>();
    private final Map<String, Long> offsetFor = new ConcurrentHashMap<>();
    private final Map<String, String> sessionFor = new ConcurrentHashMap<>();

    public String name() { return "iterm2"; }

    public String spawn(SwarmCoordinator.TeammateSpec spec) throws Exception {
        String id = spec.id() == null ? UUID.randomUUID().toString().substring(0, 8) : spec.id();
        String out = "/tmp/aethercode-iterm-" + id + ".out";
        String sessionName = "aethercode-" + id;
        // OSC escape to create a new tab; modern iTerm2 will spawn a shell.
        System.out.printf("\u001b]1337;StealFocus=%s\u0007\u001b]1337;CreateTab=%s\u0007",
                sessionName, sessionName);
        outputFileFor.put(id, out);
        offsetFor.put(id, 0L);
        sessionFor.put(id, sessionName);
        LOG.info("iterm2 backend: spawned teammate {} in session {}", id, sessionName);
        return id;
    }

    public CompletableFuture<String> sendTask(String teammateId, String task) {
        if (!outputFileFor.containsKey(teammateId)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("unknown teammate: " + teammateId));
        }
        return CompletableFuture.supplyAsync(() -> {
            // The user is expected to attach / interact with the iTerm2 tab directly.
            // The bridge records that the task was queued.
            return "queued — interact with the iTerm2 tab to send the task";
        });
    }

    public void retire(String teammateId) {
        String session = sessionFor.remove(teammateId);
        if (session != null) {
            System.out.printf("\u001b]1337;CloseTab=%s\u0007", session);
        }
        outputFileFor.remove(teammateId);
        offsetFor.remove(teammateId);
    }
}
