package org.aethercode.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Phase 2.3 / T-2-18 (spec.md §4.2): the
 * {@code ac session restore <id>} subcommand. Restores a
 * session from Trash back to the active list. Idempotent:
 * restoring a session that's already active is a no-op (the
 * RPC returns ok=true with a note).
 */
@Command(
        name = "restore",
        description = "Restore a session from Trash (T-2-18).")
public class SessionRestoreCommand implements Callable<Integer> {

    @Parameters(arity = "1", description = "Session id (e.g. c-1234).")
    String id;

    @Override
    public Integer call() throws Exception {
        Map<String, Object> r = SupervisorRpc.call("session/restore", Map.of("id", id));
        if (Boolean.TRUE.equals(r.get("ok"))) {
            System.out.println("restored " + id);
            return 0;
        }
        System.err.println("session.restore failed: " + r.getOrDefault("error", r));
        return 1;
    }
}
