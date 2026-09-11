package org.aethercode.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Phase 2.3 / T-2-18 (spec.md §1.2, §2.1): the
 * {@code ac session rename <id> <new-title>} subcommand.
 * Renames a session in place — no history is lost. The new
 * title is what the sidebar / list shows from the next render
 * onward; the title is also written to the JSONL transcript
 * on the next flush so a follow-up {@code session show} sees
 * the new name.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — renamed.</li>
 *   <li>1 — RPC error (session not found, validation, etc.).</li>
 *   <li>2 — usage error (missing args).</li>
 * </ul>
 */
@Command(
        name = "rename",
        description = "Rename a session (T-2-18).")
public class SessionRenameCommand implements Callable<Integer> {

    @Parameters(arity = "2", description = "Session id and new title (in that order).",
                paramLabel = "ID TITLE")
    List<String> args;

    @Override
    public Integer call() throws Exception {
        if (args == null || args.size() != 2) {
            System.err.println("usage: aethercode session rename <id> <new-title>");
            return 2;
        }
        String id = args.get(0);
        String title = args.get(1);
        if (title.isBlank()) {
            System.err.println("session.rename: title must not be blank");
            return 2;
        }
        if (title.length() > 200) {
            System.err.println("session.rename: title must be at most 200 characters (got "
                    + title.length() + ")");
            return 2;
        }
        Map<String, Object> r = SupervisorRpc.call("session/rename",
                Map.of("id", id, "title", title));
        if (Boolean.TRUE.equals(r.get("ok"))) {
            System.out.println("renamed " + id + " to: " + title);
            return 0;
        }
        System.err.println("session.rename failed: " + r.getOrDefault("error", r));
        return 1;
    }
}
