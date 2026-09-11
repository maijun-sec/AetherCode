package org.aethercode.talon.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Environment keys and helper constants used by the Talon runtime.
 *
 * <p>Java-native port of the runtime-level constants from
 * {@code deepagents_talon.runtime}.</p>
 */
public final class RuntimeEnv {

    private RuntimeEnv() {}

    public static final int DEFAULT_RECURSION_LIMIT = 500;
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final int DEFAULT_MAX_CONTINUATIONS = 3;
    public static final int DEFAULT_MAX_APPROVAL_ROUNDS = 50;

    public static final String CONTEXT_SIZE_ENV_KEY = "DEEPAGENTS_TALON_CONTEXT_SIZE";
    public static final String INTERRUPT_ON_TOOLS_ENV_KEY = "DEEPAGENTS_TALON_INTERRUPT_ON_TOOLS";
    public static final String RECURSION_LIMIT_ENV_KEY = "DEEPAGENTS_TALON_RECURSION_LIMIT";
    public static final String WORKSPACE_ENV = "DEEPAGENTS_TALON_WORKSPACE";

    public static final Set<String> ASYNC_SUBAGENT_TOOL_NAMES = Set.of(
            "start_async_task", "update_async_task", "cancel_async_task");

    public static final Set<String> BACKEND_ENV_ALLOWED_KEYS = Set.of(
            "CI", "CLICOLOR", "CLICOLOR_FORCE", "COLORTERM", "FORCE_COLOR", "HOME",
            "LANG", "LOGNAME", "NO_COLOR", "SHELL", "TEMP", "TERM", "TMP", "TMPDIR",
            "TZ", "USER", "XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME",
            "XDG_RUNTIME_DIR", "XDG_STATE_HOME");

    public static final String[] BACKEND_ENV_ALLOWED_PREFIXES = {"LC_"};

    public static final Set<String> BACKEND_ENV_HIJACK_KEYS = Set.of(
            "BASH_ENV", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "ENV",
            "LD_LIBRARY_PATH", "LD_PRELOAD", "PYTHONHOME", "PYTHONPATH", "ZDOTDIR");

    public static final String[] BACKEND_ENV_SECRET_MARKERS = {
            "APIKEY", "API_KEY", "AUTHORIZATION", "BEARER", "CREDENTIAL", "OAUTH",
            "PASSWORD", "SECRET", "TOKEN"};

    public static final String[] RETRYABLE_BAD_REQUEST_MARKERS = {
            "failed to parse", "tool_call", "tool call",
            "context length", "context window", "context limit",
            "maximum context", "max context", "input too long", "request too large"};

    public static final String[] RETRYABLE_MESSAGE_MARKERS = {
            "failed to parse", "tool_call", "tool call",
            "context length", "context window", "context limit",
            "maximum context", "max context", "input too long", "request too large",
            "connection aborted", "connection closed", "connection lost",
            "connection refused", "connection reset", "connection timed out",
            "read timeout", "timed out", "temporarily unavailable", "temporary failure"};

    public static final int BAD_REQUEST_STATUS_CODE = 400;
    public static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(
            408, 409, 413, 429, 500, 502, 503, 504);

    public static final String CONTINUATION_NUDGE =
            "Your action budget was exhausted mid-task. Continue working and complete the task. "
                    + "If you have already finished, provide your final answer now.";
    public static final String FORCE_SUMMARY_PROMPT =
            "You ran out of actions. Provide a concise summary of everything you have "
                    + "accomplished so far. Do not call any more tools.";

    public static final String CRON_AUTO_DENY_MESSAGE =
            "Tool approval is unavailable for scheduled runs; skipped the gated tool call.";
    public static final String CHANNEL_AUTO_DENY_MESSAGE =
            "Tool approval is unavailable on this channel; skipped the gated tool call.";

    public static final String SAFE_BACKEND_PATH =
            "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin";

    /**
     * Build a child-process environment from a parent mapping, applying
     * the Talon allow/deny policy.
     */
    public static Map<String, String> backendChildEnv(Map<String, String> env) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (isAllowedBackendEnvKey(entry.getKey()) && !isScrubbedBackendEnvKey(entry.getKey())) {
                values.put(entry.getKey(), entry.getValue());
            }
        }
        values.put("PATH", SAFE_BACKEND_PATH);
        return values;
    }

    public static boolean isAllowedBackendEnvKey(String key) {
        if (BACKEND_ENV_ALLOWED_KEYS.contains(key)) {
            return true;
        }
        for (String prefix : BACKEND_ENV_ALLOWED_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isScrubbedBackendEnvKey(String key) {
        if (BACKEND_ENV_HIJACK_KEYS.contains(key)) {
            return true;
        }
        if (key.startsWith("LANGSMITH_") || key.startsWith("LANGCHAIN_")) {
            return true;
        }
        for (String marker : BACKEND_ENV_SECRET_MARKERS) {
            if (key.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
