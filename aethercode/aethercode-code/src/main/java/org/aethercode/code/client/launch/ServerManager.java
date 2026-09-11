package org.aethercode.code.client.launch;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Process-level state for the local agent server.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.client.launch.server_manager} module.
 * Tracks the server pid, port, log file path, and liveness.</p>
 */
public final class ServerManager {
    private static final Logger LOGGER = Logger.getLogger(ServerManager.class.getName());

    /** Environment key carrying the recorded server pid. */
    public static final String ENV_PID = "DEEPAGENTS_CODE_PID";
    /** Environment key carrying the recorded server port. */
    public static final String ENV_PORT = "DEEPAGENTS_CODE_PORT";
    /** File under which the manager persists its state. */
    public static final Path STATE_FILE = Path.of(
            System.getProperty("user.home"), ".deepagents", "server-state.json");

    private static final Map<String, ServerState> STATES = new HashMap<>();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private ServerManager() {}

    /** Process record for a running server. */
    public record ServerState(String name, long pid, int port, Path logFile) {
    }

    /**
     * Persist a server's state.
     */
    public static void record(ServerState state) {
        STATES.put(state.name(), state);
        persist();
    }

    /**
     * Look up a server's recorded state.
     */
    public static ServerState lookup(String name) {
        ensureLoaded();
        return STATES.get(name);
    }

    /**
     * Drop a server's recorded state.
     */
    public static void forget(String name) {
        STATES.remove(name);
        persist();
    }

    /**
     * Find a free local TCP port.
     */
    public static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not find a free port; defaulting to 0", e);
            return 0;
        }
    }

    private static void ensureLoaded() {
        if (INITIALIZED.compareAndSet(false, true)) {
            if (Files.isRegularFile(STATE_FILE)) {
                try {
                    String text = Files.readString(STATE_FILE);
                    Object decoded = org.aethercode.code.plugins.PluginMiniJson.parse(text);
                    if (decoded instanceof java.util.Map<?, ?> map) {
                        for (Map.Entry<?, ?> e : map.entrySet()) {
                            if (!(e.getKey() instanceof String k)) continue;
                            if (!(e.getValue() instanceof java.util.Map<?, ?> v)) continue;
                            STATES.put(k, new ServerState(
                                    k,
                                    ((Number) v.get("pid")).longValue(),
                                    ((Number) v.get("port")).intValue(),
                                    Path.of(String.valueOf(v.get("log")))));
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Could not load server-state file", e);
                }
            }
        }
    }

    private static void persist() {
        try {
            Files.createDirectories(STATE_FILE.getParent());
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, ServerState> e : STATES.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey()).append("\":{");
                ServerState s = e.getValue();
                sb.append("\"pid\":").append(s.pid())
                        .append(",\"port\":").append(s.port())
                        .append(",\"log\":\"").append(s.logFile()).append("\"}");
            }
            sb.append('}');
            Files.writeString(STATE_FILE, sb.toString());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not persist server-state file", e);
        }
    }
}
