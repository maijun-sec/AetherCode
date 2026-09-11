package org.aethercode.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Phase 2.3 / T-2-18 (spec.md §4.2, design.md §3.1): the
 * {@code ac session trash} subcommand — three sub-actions
 * behind one command:
 *
 * <ul>
 *   <li>{@code ac session trash} (no flags) — list trashed
 *       sessions, sorted by {@code trashed_at} desc.</li>
 *   <li>{@code ac session trash --restore <id>} — restore a
 *       specific session (same as {@code ac session restore}
 *       but accessible from the trash panel).</li>
 *   <li>{@code ac session trash --empty} — permanently delete
 *       every trashed session, with a confirmation prompt
 *       (skipped when {@code --yes} is also passed).</li>
 * </ul>
 *
 * <p>The three sub-actions share a command class because
 * picocli's subcommand dispatch is awkward when one of the
 * sub-actions is the "default" (no subcommand). Putting them
 * behind option flags keeps the CLI ergonomic while remaining
 * a single class.
 */
@Command(
        name = "trash",
        description = "List, restore, or empty the session Trash (T-2-18).")
public class SessionTrashCommand implements Callable<Integer> {

    @Option(names = {"--empty"}, description = "Permanently delete every trashed session.")
    boolean empty;

    @Option(names = {"--restore"}, description = "Restore the given session from Trash.")
    String restore;

    @Option(names = {"--yes", "-y"},
            description = "Skip the confirmation prompt for --empty (irreversible).")
    boolean yes;

    @Override
    public Integer call() throws Exception {
        if (empty && restore != null) {
            System.err.println("session.trash: --empty and --restore are mutually exclusive");
            return 2;
        }
        if (empty) {
            return emptyTrash();
        }
        if (restore != null) {
            return restoreOne(restore);
        }
        return listTrashed();
    }

    private int listTrashed() throws Exception {
        Map<String, Object> r = SupervisorRpc.call("session/list",
                Map.of("trashed", true, "limit", 500));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.getOrDefault("sessions", List.of());
        // Always print the header, even when empty.
        System.out.println("ID\tTITLE\tTRASHED_AT");
        for (Map<String, Object> row : rows) {
            System.out.println(String.join("\t",
                    str(row.get("id")),
                    str(row.get("title")),
                    str(row.get("trashedAt"))));
        }
        System.err.println("session.trash: " + rows.size() + " trashed session(s)");
        return 0;
    }

    private int restoreOne(String id) throws Exception {
        Map<String, Object> r = SupervisorRpc.call("session/restore", Map.of("id", id));
        if (Boolean.TRUE.equals(r.get("ok"))) {
            System.out.println("restored " + id);
            return 0;
        }
        System.err.println("session.trash: restore failed: "
                + r.getOrDefault("error", r));
        return 1;
    }

    private int emptyTrash() throws Exception {
        if (!yes) {
            // Best-effort confirmation: read a single line
            // from stdin. The user must type "yes" (not "y"
            // or "Y") to confirm — the verb matches what the
            // spec example shows.
            System.out.print("permanently delete every trashed session? (type 'yes' to confirm) ");
            System.out.flush();
            String line = System.console() != null
                    ? System.console().readLine()
                    : new java.util.Scanner(System.in).nextLine();
            if (line == null || !line.trim().equalsIgnoreCase("yes")) {
                System.out.println("aborted");
                return 1;
            }
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("empty", true);
        Map<String, Object> r = SupervisorRpc.call("session/trash", params);
        if (Boolean.TRUE.equals(r.get("ok"))) {
            int n = ((Number) r.getOrDefault("deleted", 0)).intValue();
            System.out.println("emptied Trash (" + n + " session(s) permanently deleted)");
            return 0;
        }
        System.err.println("session.trash: empty failed: "
                + r.getOrDefault("error", r));
        return 1;
    }

    private static String str(Object o) {
        if (o == null) return "";
        return o.toString().replace('\t', ' ');
    }
}
