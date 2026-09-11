package org.aethercode.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Phase 2.3 / T-2-18 (spec.md §1.3, design.md §3.1): the
 * {@code ac session list} subcommand. Prints a tab-separated
 * table of sessions sorted by {@code last_active_at} desc,
 * matching the wire shape the desktop's
 * {@code SessionListVirtual.tsx} consumes.
 *
 * <p>Filters (all optional):
 * <ul>
 *   <li>{@code --cwd <path>} — limit to one project.</li>
 *   <li>{@code --since <epoch-ms>} — only sessions active since.</li>
 *   <li>{@code --query <text>} — substring match on title / cwd / preview.</li>
 *   <li>{@code --limit N} — cap rows (default 100, max 500).</li>
 *   <li>{@code --trashed} — list the Trash instead of the active list.</li>
 * </ul>
 *
 * <p>Output (tab-separated for grep-friendly scripting):
 * <pre>
 *   ID          TITLE                 CWD                 LAST_ACTIVE       TOKENS  STATE
 *   c-123       Add login form        /work/proj         3 hours ago       1234    COMPLETED
 * </pre>
 * A header is always printed, even when the list is empty, so
 * downstream {@code awk} / {@code column} scripts don't break.
 */
@Command(
        name = "list",
        description = "List sessions (active or trashed, T-2-18).")
public class SessionListCommand implements Callable<Integer> {

    @Option(names = {"--cwd"}, description = "Filter by project cwd.")
    String cwd;

    @Option(names = {"--since"}, description = "Only sessions active since this epoch-ms.")
    Long since;

    @Option(names = {"--query", "-q"}, description = "Substring match on title / cwd / preview.")
    String query;

    @Option(names = {"--limit"}, description = "Max rows (default 100, max 500).")
    Integer limit;

    @Option(names = {"--trashed"}, description = "List the Trash instead of the active list.")
    boolean trashed;

    @Override
    public Integer call() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        if (cwd != null && !cwd.isBlank())     params.put("cwd", cwd);
        if (since != null)                     params.put("since", since);
        if (query != null && !query.isBlank()) params.put("query", query);
        if (limit != null)                     params.put("limit", limit);
        if (trashed)                           params.put("trashed", true);

        Map<String, Object> r = SupervisorRpc.call("session/list", params);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.getOrDefault("sessions", List.of());

        // Header is always emitted, even when empty.
        System.out.println("ID\tTITLE\tCWD\tLAST_ACTIVE\tTOKENS\tSTATE");
        for (Map<String, Object> row : rows) {
            System.out.println(formatRow(row));
        }
        int total = ((Number) r.getOrDefault("total", rows.size())).intValue();
        if (rows.size() < total) {
            System.err.println("session.list: showing " + rows.size()
                    + " of " + total + " matching session(s); use --limit to see more");
        } else {
            System.err.println("session.list: " + rows.size() + " session(s)");
        }
        return 0;
    }

    private static String formatRow(Map<String, Object> r) {
        return String.join("\t",
                str(r.get("id")),
                str(r.get("title")),
                str(r.get("cwd")),
                str(r.get("lastActiveAt")),
                str(r.get("tokensTotal")),
                str(r.get("state")));
    }

    private static String str(Object o) {
        if (o == null) return "";
        String s = o.toString();
        // Tabs in fields would corrupt the column layout; the
        // session title and cwd are user-provided so a stray
        // tab is realistic.
        return s.replace('\t', ' ');
    }
}
