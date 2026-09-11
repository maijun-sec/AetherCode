package org.aethercode.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Phase 2.3 / T-2-18 (spec.md §2, §3): the
 * {@code ac session show <id>} subcommand. Prints a
 * human-readable dump of a session: header, transcript, tool
 * call summary, TODO list, token total.
 *
 * <p>The output is intentionally verbose — this is the
 * headless equivalent of opening the session in the desktop
 * chat panel. JSON output is available via {@code --json}
 * for piping into {@code jq}.
 */
@Command(
        name = "show",
        description = "Show a single session's metadata + transcript (T-2-18).")
public class SessionShowCommand implements Callable<Integer> {

    @Parameters(arity = "1", description = "Session id (e.g. c-1234).")
    String id;

    @Option(names = {"--json"}, description = "Emit the raw JSON response on stdout.")
    boolean json;

    @Option(names = {"--limit"},
            description = "Max transcript events to print (default 200).")
    int limit = 200;

    @Override
    public Integer call() throws Exception {
        Map<String, Object> params = Map.of("id", id, "limit", limit);
        Map<String, Object> r = SupervisorRpc.call("session/show", params);
        if (r.containsKey("error")) {
            System.err.println("session.show: " + r.get("error"));
            return 1;
        }
        if (json) {
            // Pretty-print minimal JSON via the same shape the
            // desktop's SessionListVirtual consumes. We don't
            // pull in Jackson for the CLI print path (see
            // WorkflowLintCommand for the same reasoning).
            System.out.println(toJson(r));
        } else {
            printHuman(r);
        }
        return 0;
    }

    private static void printHuman(Map<String, Object> r) {
        System.out.println("session: " + r.getOrDefault("id", ""));
        System.out.println("  title:       " + r.getOrDefault("title", ""));
        System.out.println("  cwd:         " + r.getOrDefault("cwd", ""));
        System.out.println("  model:       " + r.getOrDefault("model", ""));
        System.out.println("  started_at:  " + r.getOrDefault("startedAt", ""));
        System.out.println("  last_active: " + r.getOrDefault("lastActiveAt", ""));
        System.out.println("  state:       " + r.getOrDefault("state", ""));
        System.out.println("  tokens:      in=" + r.getOrDefault("tokensIn", 0)
                + " out=" + r.getOrDefault("tokensOut", 0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) r.getOrDefault("events", List.of());
        System.out.println("  events:      " + events.size());
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> e = events.get(i);
            System.out.println("    [" + (i + 1) + "] " + e.getOrDefault("type", "")
                    + "  " + truncate(e.getOrDefault("preview", "").toString(), 80));
        }
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    /** Minimal hand-rolled JSON pretty-printer. The CLI
     *  commands don't need a full JSON library — the response
     *  shape is well-known and small. */
    private static String toJson(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return "\"" + jsonEscape(s) + "\"";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(list.get(i)));
            }
            return sb.append("]").toString();
        }
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(jsonEscape(String.valueOf(e.getKey()))).append("\":");
                sb.append(toJson(e.getValue()));
            }
            return sb.append("}").toString();
        }
        return "\"" + jsonEscape(o.toString()) + "\"";
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
