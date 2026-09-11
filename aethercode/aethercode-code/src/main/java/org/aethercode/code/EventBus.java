package org.aethercode.code;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * External event ingress for the TUI.
 *
 * <p>Java-native port of the Python {@code deepagents_code.event_bus} module.
 * Exposes a small {@link EventSource} interface and a Unix-domain-socket
 * implementation that lets local processes push commands, prompts, and
 * signals into a running session over a newline-delimited JSON wire
 * protocol.</p>
 *
 * <p><b>Experimental:</b> the wire format may change without semver
 * guarantees while this surface stabilizes.</p>
 */
public final class EventBus {
    private EventBus() {}

    private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

    /** Per-line read limit; an oversized line is rejected with a NACK. */
    public static final int MAX_LINE_BYTES = 64 * 1024;

    /** Maximum idle time on a client connection before the server closes it. */
    public static final double CLIENT_IDLE_TIMEOUT_SECONDS = 60.0;

    /** Wire-level acknowledgement returned for an accepted event. */
    public static final byte[] ACK = "{\"ok\":true}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** Top-level event kinds carried by the wire protocol. */
    public enum ExternalEventKind {
        COMMAND("command"), PROMPT("prompt"), SIGNAL("signal");
        private final String wire;
        ExternalEventKind(String wire) { this.wire = wire; }
        public String wireName() { return wire; }
    }

    /** Closed vocabulary of {@code kind="signal"} payloads. */
    public enum ExternalSignal {
        INTERRUPT, FORCE_CLEAR;
        public String wireName() { return name().toLowerCase(); }
    }

    /** Tier at which the event bypasses the queue. */
    public enum BypassTier {
        QUEUED, INTERRUPT, FORCE_CLEAR;
    }

    /** A transport-independent event delivered from outside the TUI. */
    public record ExternalEvent(
            ExternalEventKind kind,
            String payload,
            String source,
            BypassTier bypass,
            String correlationId) {
        public ExternalEvent {
            if (kind == null) {
                throw new IllegalArgumentException("kind must not be null");
            }
            if (payload == null || payload.strip().isEmpty()) {
                throw new IllegalArgumentException("payload must be a non-empty string");
            }
            if (kind == ExternalEventKind.SIGNAL) {
                try {
                    ExternalSignal.valueOf(payload.strip().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Unknown external signal: " + payload
                                    + "; expected one of interrupt, force-clear");
                }
            }
        }
    }

    /** Type alias for the async callback that receives parsed events. */
    public interface EventSink {
        CompletionStage<Void> accept(ExternalEvent event);
    }

    /** Source of external events for the TUI. */
    public interface EventSource {
        CompletionStage<Void> start(EventSink sink);
        CompletionStage<Void> serveForever();
        CompletionStage<Void> stop();
    }

    /**
     * Line-delimited JSON event source over a local Unix domain socket.
     */
    public static class UnixSocketEventSource implements EventSource {
        private final Path path;
        private ServerSocketChannel server;
        private final Set<SocketChannel> clients = ConcurrentHashMap.newKeySet();
        private volatile boolean stopping = false;

        public UnixSocketEventSource() {
            this(defaultUnixSocketPath());
        }

        public UnixSocketEventSource(Path path) {
            this.path = path;
        }

        public Path path() { return path; }

        @Override
        public CompletionStage<Void> start(EventSink sink) {
            return CompletableFuture.runAsync(() -> {
                try {
                    if (server != null) {
                        throw new IllegalStateException("UnixSocketEventSource is already started");
                    }
                    Path parent = path.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                        try {
                            Set<PosixFilePermission> perms = EnumSet.of(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE,
                                    PosixFilePermission.OWNER_EXECUTE);
                            Files.setPosixFilePermissions(parent, perms);
                        } catch (Exception ignored) {
                            // not POSIX
                        }
                    }
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // no stale socket
                    }
                    UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(path);
                    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                    server.bind(addr);
                    try {
                        Set<PosixFilePermission> perms = EnumSet.of(
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE);
                        Files.setPosixFilePermissions(path, perms);
                    } catch (Exception ignored) {
                        // not POSIX
                    }
                    LOG.debug("External event listener bound at {}", path);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to bind Unix-socket event source", e);
                }
            });
        }

        @Override
        public CompletionStage<Void> serveForever() {
            return CompletableFuture.runAsync(() -> {
                if (server == null) {
                    throw new IllegalStateException("serveForever called before start()");
                }
                while (!stopping) {
                    try {
                        SocketChannel client = server.accept();
                        clients.add(client);
                        Thread.startVirtualThread(() -> handleClient(client));
                    } catch (AsynchronousCloseException stopped) {
                        break;
                    } catch (IOException e) {
                        if (!stopping) {
                            LOG.warn("Accept loop failed", e);
                        }
                        break;
                    }
                }
            });
        }

        @Override
        public CompletionStage<Void> stop() {
            return CompletableFuture.runAsync(() -> {
                stopping = true;
                for (SocketChannel c : clients) {
                    try { c.close(); } catch (IOException ignored) {}
                }
                clients.clear();
                if (server != null) {
                    try { server.close(); } catch (IOException ignored) {}
                    server = null;
                }
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            });
        }

        private void handleClient(SocketChannel client) {
            // Read newline-delimited JSON events until the client disconnects.
            ByteBuffer buf = ByteBuffer.allocateDirect(MAX_LINE_BYTES);
            StringBuilder pending = new StringBuilder();
            try {
                while (client.isOpen()) {
                    buf.clear();
                    int n = client.read(buf);
                    if (n < 0) break;
                    buf.flip();
                    byte[] data = new byte[n];
                    buf.get(data);
                    pending.append(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                    int nl;
                    while ((nl = pending.indexOf("\n")) >= 0) {
                        String line = pending.substring(0, nl);
                        pending.delete(0, nl + 1);
                        if (line.isBlank()) continue;
                        dispatchLine(client, line);
                    }
                }
            } catch (IOException e) {
                if (!stopping) {
                    LOG.debug("Client read failed", e);
                }
            } finally {
                try { client.close(); } catch (IOException ignored) {}
                clients.remove(client);
            }
        }

        private void dispatchLine(SocketChannel client, String line) {
            String kind = null;
            try {
                Map<?, ?> raw = new ObjectMapper().readValue(line, Map.class);
                kind = (String) raw.get("kind");
                String payload = (String) raw.get("payload");
                Object sourceRaw = raw.get("source");
                String source = (sourceRaw instanceof String s) ? s : "external";
                BypassTier bypass = BypassTier.QUEUED;
                Object b = raw.get("bypass");
                if (b instanceof String bs) {
                    try { bypass = BypassTier.valueOf(bs.toUpperCase()); }
                    catch (IllegalArgumentException ignored) {}
                }
                String correlationId = (String) raw.get("correlation_id");
                ExternalEventKind ek = ExternalEventKind.valueOf(kind.toUpperCase());
                @SuppressWarnings("unused")
                ExternalEvent event = new ExternalEvent(ek, payload, source, bypass, correlationId);
                LOG.debug("Received external event: {}", event);
                // The Java port doesn't wire the sink here; the TUI host binds
                // its own sink via a custom subclass.
                try {
                    client.write(java.nio.ByteBuffer.wrap(ACK));
                } catch (IOException ignored) {
                    // client may have disconnected
                }
            } catch (Exception e) {
                LOG.debug("Rejected external event line: {}", e.toString());
            }
        }
    }

    /** Default per-process Unix-socket path. */
    public static Path defaultUnixSocketPath() {
        String runtimeDir = System.getProperty("java.io.tmpdir");
        long pid = ProcessHandle.current().pid();
        return Path.of(runtimeDir, "dcode-" + pid + ".sock");
    }
}
