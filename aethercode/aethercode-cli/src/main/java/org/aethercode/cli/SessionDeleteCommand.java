package org.aethercode.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Phase 2.3 / T-2-18 (spec.md §4): the
 * {@code ac session delete <id>} subcommand. Soft-deletes a
 * session: it moves to Trash, where it sits for 30 days
 * before a background reaper permanently removes it. Use
 * {@code ac session restore <id>} to undo, or
 * {@code ac session trash --empty} to purge early.
 */
@Command(
        name = "delete",
        description = "Soft-delete a session — moves it to Trash (T-2-18).")
public class SessionDeleteCommand implements Callable<Integer> {

    @Parameters(arity = "1", description = "Session id (e.g. c-1234).")
    String id;

    @Override
    public Integer call() throws Exception {
        Map<String, Object> r = SupervisorRpc.call("session/delete", Map.of("id", id));
        if (Boolean.TRUE.equals(r.get("ok"))) {
            System.out.println("deleted " + id + " (moved to Trash; "
                    + "use `ac session restore " + id + "` to undo within 30 days)");
            return 0;
        }
        System.err.println("session.delete failed: " + r.getOrDefault("error", r));
        return 1;
    }
}
