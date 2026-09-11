package org.aethercode.cli;

import org.aethercode.tasks.supervisor.SupervisorClient;
import org.aethercode.tasks.supervisor.SupervisorHome;
import org.aethercode.tasks.supervisor.SupervisorProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Phase 2.3 (T-2-18): tiny shim that the session/* CLI
 * subcommands use to talk to the supervisor over the
 * JSON-RPC socket. The same pattern as
 * {@code aethercode.tasks.cli.TaskSession}, but with a
 * pluggable {@link Caller} so the headless tests can run
 * without booting a real supervisor.
 *
 * <p>The default {@link #defaultCaller()} boots a private
 * supervisor in-process if one is not already running (the
 * canonical user has a long-lived one started by the TUI /
 * desktop; the CLI is a fallback). The pluggable seam exists
 * for tests: each test installs a {@link Caller} that
 * returns canned responses, exercises the command, and asserts
 * the wire shape.
 *
 * <p>The {@link Caller} functional shape is intentionally
 * small: a method name + a params map → a result map. The
 * shim is the single point of contact with the supervisor
 * socket; the per-command classes don't import
 * {@link SupervisorClient} directly.
 */
public final class SupervisorRpc {

    private static final Logger LOG = LoggerFactory.getLogger(SupervisorRpc.class);

    /** Pluggable RPC entry point. The default boots a real
     *  supervisor socket; tests install a fake. */
    @FunctionalInterface
    public interface Caller {
        /** Call {@code method} with {@code params} and return
         *  the result. Must not throw on a normal "method
         *  not found" — the supervisor's JSON-RPC layer
         *  already handles that. */
        Map<String, Object> call(String method, Map<String, Object> params) throws Exception;
    }

    private static final AtomicReference<Caller> ACTIVE = new AtomicReference<>(defaultCaller());

    private SupervisorRpc() {}

    /** Install a custom caller (tests only). */
    public static void installForTesting(Caller caller) {
        ACTIVE.set(caller);
    }

    /** Restore the default real-supervisor caller (tests only). */
    public static void resetToDefault() {
        ACTIVE.set(defaultCaller());
    }

    /** Convenience: call the active caller. */
    public static Map<String, Object> call(String method, Map<String, Object> params) throws Exception {
        return ACTIVE.get().call(method, params);
    }

    /** Build the default caller. The lambda opens a short-lived
     *  socket connection per call (cheap; the supervisor's
     *  accept loop is single-threaded but line-buffered). For
     *  bulk operations the test or a future optimization can
     *  install a cached-socket caller. */
    public static Caller defaultCaller() {
        return (method, params) -> {
            Path lock = SupervisorHome.dir().resolve("supervisor.sock");
            SupervisorProcess owned = null;
            SupervisorClient client;
            try {
                if (!Files.exists(lock)) {
                    LOG.info("supervisor lock file not found at {} — starting a private one",
                            lock);
                    Path db = SupervisorHome.dir().resolve("sessions.db");
                    owned = new SupervisorProcess(db);
                    owned.start();
                }
                client = new SupervisorClient(lock);
                client.connect();
                try {
                    return client.callMap(method, params);
                } finally {
                    try { client.close(); } catch (Exception ignored) {}
                }
            } finally {
                if (owned != null) {
                    try { owned.stop(); } catch (Exception ignored) {}
                }
            }
        };
    }
}
