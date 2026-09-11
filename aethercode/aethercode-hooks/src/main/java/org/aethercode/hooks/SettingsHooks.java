package org.aethercode.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.core.message.Message;
import org.aethercode.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Settings-driven hook loader. Reads a {@code hooks} block from the project
 * {@code settings.json} and turns each entry into a registered hook. Modelled after the
 * TS {@code src/utils/settings/hooks.ts}.
 *
 * <p>Format:
 * <pre>
 *   "hooks": {
 *     "PreToolUse": [
 *       { "matcher": "bash", "action": "deny", "reason": "no shell from this project" }
 *     ],
 *     "PostToolUse": [
 *       { "matcher": "*", "action": "log", "path": "~/.aethercode/audit.log" }
 *     ]
 *   }
 * </pre>
 *
 * <p>Supported actions:
 * <ul>
 *   <li>{@code deny} — short-circuits the call with a Block verdict</li>
 *   <li>{@code log} — appends a one-line record to {@code path} (PostToolUse only)</li>
 *   <li>{@code allow} — explicit allow (no-op for the engine but useful for documentation)</li>
 * </ul>
 */
public final class SettingsHooks {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsHooks.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SettingsHooks() {}

    /** Load hooks from a settings.json file. Missing file is fine — returns the registry unchanged. */
    public static HookRegistry loadFrom(Path settingsFile, HookRegistry into) {
        if (!Files.exists(settingsFile)) return into;
        try {
            Map<String, Object> raw = MAPPER.readValue(Files.readString(settingsFile), Map.class);
            return loadFromMap(raw, into);
        } catch (IOException e) {
            LOG.warn("failed to read settings hooks: {}", e.getMessage());
            return into;
        }
    }

    @SuppressWarnings("unchecked")
    public static HookRegistry loadFromMap(Map<String, Object> raw, HookRegistry into) {
        if (raw == null) return into;
        Object hooksObj = raw.get("hooks");
        if (!(hooksObj instanceof Map)) return into;
        Map<String, Object> hooks = (Map<String, Object>) hooksObj;
        registerKind(into, Hook.Kind.PRE_TOOL_USE, (List<Map<String, Object>>) hooks.get("PreToolUse"));
        registerKind(into, Hook.Kind.POST_TOOL_USE, (List<Map<String, Object>>) hooks.get("PostToolUse"));
        registerKind(into, Hook.Kind.USER_PROMPT_SUBMIT, (List<Map<String, Object>>) hooks.get("UserPromptSubmit"));
        registerKind(into, Hook.Kind.STOP, (List<Map<String, Object>>) hooks.get("Stop"));
        return into;
    }

    private static void registerKind(HookRegistry into, Hook.Kind kind, List<Map<String, Object>> rules) {
        if (rules == null) return;
        for (Map<String, Object> rule : rules) {
            String matcher = str(rule.get("matcher"));
            String action = str(rule.get("action"));
            String reason = str(rule.get("reason"));
            String logPath = str(rule.get("path"));
            if (action == null) continue;
            into.register(new SettingsHook(kind, matcher, action, reason, logPath));
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    /** One settings-driven hook. */
    private static final class SettingsHook implements Hook {
        private final Kind kind;
        private final String matcher;
        private final String action;
        private final String reason;
        private final String logPath;

        SettingsHook(Kind kind, String matcher, String action, String reason, String logPath) {
            this.kind = kind;
            this.matcher = matcher;
            this.action = action;
            this.reason = reason;
            this.logPath = logPath;
        }

        @Override public Kind kind() { return kind; }

        @Override
        public CompletableFuture<Outcome> run(HookContext ctx) {
            if (!matches(ctx)) return CompletableFuture.completedFuture(new Outcome.Continue());
            return switch (action) {
                case "deny" -> CompletableFuture.completedFuture(
                        new Outcome.Block(reason != null ? reason : "denied by settings hook"));
                case "log" -> {
                    if (logPath != null) {
                        try {
                            String line = String.format("%s\t%s\t%s\t%s%n",
                                    java.time.Instant.now(), kind, ctx.toolName(),
                                    ctx.toolInput() == null ? "" : ctx.toolInput());
                            java.nio.file.Files.createDirectories(java.nio.file.Path.of(logPath).getParent());
                            java.nio.file.Files.writeString(java.nio.file.Path.of(logPath), line,
                                    java.nio.file.StandardOpenOption.CREATE,
                                    java.nio.file.StandardOpenOption.APPEND);
                        } catch (IOException e) {
                            LOG.warn("hook log failed: {}", e.getMessage());
                        }
                    }
                    yield CompletableFuture.completedFuture(new Outcome.Continue());
                }
                default -> CompletableFuture.completedFuture(new Outcome.Continue());
            };
        }

        private boolean matches(HookContext ctx) {
            if (matcher == null || matcher.equals("*") || matcher.isBlank()) return true;
            return matcher.equals(ctx.toolName());
        }
    }
}
