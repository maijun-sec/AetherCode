package org.aethercode.code.hooks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Legacy dotted-event migration helpers for Hooks v2 configuration.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.migration} module. Each distinct legacy
 * event name becomes its own matcher group so a single entry
 * subscribed to multiple events still runs once per mapped name with
 * the matching reconstructed stdin payload.</p>
 */
public final class Migration {

    /** Outer runner grace so the nested adapter timeout can fire first on Windows. */
    public static final double ADAPTER_OUTER_TIMEOUT_SECONDS = Env.HOOK_SUBPROCESS_TIMEOUT + 1.0;

    /** Name of the Python module invoked as the migration adapter. */
    public static final String ADAPTER_MODULE = "deepagents_code.hooks.migration";

    /** Argument count expected by the adapter (legacy event + encoded argv). */
    public static final int ADAPTER_ARGUMENT_COUNT = 2;

    private static final Map<String, MappedEvent> LEGACY_EVENT_MAP = Map.of(
            "session.start", new MappedEvent(HookEvent.USER_PROMPT_SUBMIT, null),
            "user.prompt", new MappedEvent(HookEvent.USER_PROMPT_SUBMIT, null),
            "task.complete", new MappedEvent(HookEvent.NOTIFICATION, "agent_completed"),
            "session.end", new MappedEvent(HookEvent.SESSION_END, null),
            "context.offload", new MappedEvent(HookEvent.PRE_COMPACT, "manual"),
            "context.compact", new MappedEvent(HookEvent.PRE_COMPACT, "manual"),
            "input.required", new MappedEvent(HookEvent.NOTIFICATION, "agent_needs_input"));

    private static final Set<String> THREAD_ID_EVENTS = Set.of(
            "session.start", "task.complete", "session.end");

    private Migration() {}

    /** Map of legacy event name to (canonical event, optional matcher). */
    public record MappedEvent(HookEvent event, String matcher) {}

    /**
     * Convert legacy dotted-event hook entries into Hooks v2
     * configuration.
     */
    public static HookConfigTypes.HooksConfig migrateLegacyHooks(
            List<Map<String, Object>> legacyHooks) {
        Map<HookEvent, List<HookConfigTypes.MatcherGroup>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> entry : legacyHooks) {
            Object command = entry.get("command");
            if (!(command instanceof List<?> argv) || argv.isEmpty()) continue;
            List<String> argvList = new ArrayList<>();
            for (Object part : argv) {
                if (!(part instanceof String s)) {
                    argvList = null;
                    break;
                }
                argvList.add(s);
            }
            if (argvList == null) continue;
            Object events = entry.get("events");
            List<String> eventNames;
            if (events == null
                    || (events instanceof List<?> l && l.isEmpty())) {
                eventNames = new ArrayList<>(LEGACY_EVENT_MAP.keySet());
            } else if (events instanceof List<?> list) {
                eventNames = new ArrayList<>();
                for (Object name : list) {
                    if (name instanceof String s && !eventNames.contains(s)) {
                        eventNames.add(s);
                    }
                }
            } else {
                continue;
            }
            for (String eventName : eventNames) {
                MappedEvent mapped = LEGACY_EVENT_MAP.get(eventName);
                if (mapped == null) continue;
                List<String> adapterArgv = adapterArgv(argvList, eventName);
                grouped.computeIfAbsent(mapped.event(), k -> new ArrayList<>()).add(
                        new HookConfigTypes.MatcherGroup(
                                mapped.matcher(),
                                List.of(new HookConfigTypes.CommandHandlerSpec(
                                        "command",
                                        shellCommand(adapterArgv),
                                        adapterArgv,
                                        ADAPTER_OUTER_TIMEOUT_SECONDS,
                                        null))));
            }
        }
        return new HookConfigTypes.HooksConfig(grouped);
    }

    /** Return the argv that runs the legacy migration adapter. */
    public static List<String> adapterArgv(List<String> originalArgv, String legacyEvent) {
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(toJsonBytes(originalArgv));
        return List.of("python", "-m", ADAPTER_MODULE, legacyEvent, encoded);
    }

    private static String shellCommand(List<String> argv) {
        if (isWindows()) {
            return windowsShellCommand(argv);
        }
        return posixShellCommand(argv);
    }

    private static String windowsShellCommand(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < argv.size(); i++) {
            if (i > 0) sb.append(' ');
            String arg = argv.get(i);
            boolean needsQuoting = arg.isEmpty()
                    || arg.chars().anyMatch(c -> c == ' ' || c == '\t' || c == '"');
            if (!needsQuoting) {
                sb.append(arg);
            } else {
                sb.append('"');
                sb.append(arg.replace("\"", "\\\""));
                sb.append('"');
            }
        }
        return sb.toString();
    }

    private static String posixShellCommand(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < argv.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(posixQuote(argv.get(i)));
        }
        return sb.toString();
    }

    private static String posixQuote(String arg) {
        if (arg.isEmpty()) return "''";
        boolean safe = arg.chars().allMatch(c -> Character.isLetterOrDigit(c)
                || "_-./@%+=:".indexOf(c) >= 0);
        if (safe) return arg;
        return "'" + arg.replace("'", "'\"'\"'") + "'";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static byte[] toJsonBytes(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsBytes(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encode adapter argv", ex);
        }
    }

    /**
     * Return whether {@code data} looks like the legacy list-shaped
     * hooks document.
     */
    @SuppressWarnings("unchecked")
    public static boolean isLegacyHooksDocument(Object data) {
        if (!(data instanceof Map<?, ?> map)) return false;
        Object hooks = map.get("hooks");
        if (!(hooks instanceof List<?> list)) return false;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) return false;
        }
        return true;
    }

    /**
     * Build the legacy-shaped payload a migrated hook expects on
     * stdin. Reused by the Python adapter; kept here so a Java caller
     * can produce the same bytes when it has the wire payload at
     * hand.
     */
    public static byte[] legacyPayload(String legacyEvent, Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event", legacyEvent);
        if (THREAD_ID_EVENTS.contains(legacyEvent)) {
            Object threadId = payload.get("session_id");
            if (threadId instanceof String s) {
                out.put("thread_id", s);
            }
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsBytes(out);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encode legacy payload", ex);
        }
    }
}
