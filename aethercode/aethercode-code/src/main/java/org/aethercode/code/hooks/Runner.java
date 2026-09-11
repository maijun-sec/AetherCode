package org.aethercode.code.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.code.hooks.WireTypes.HookSpecificOutput;
import org.aethercode.code.hooks.WireTypes.HookWireOutput;
import org.aethercode.code.hooks.WireTypes.PermissionAllow;
import org.aethercode.code.hooks.WireTypes.PermissionDecision;
import org.aethercode.code.hooks.WireTypes.PermissionDeny;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bounded asynchronous command execution for Hooks v2.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.runner} module. The runner spawns the
 * command as a subprocess, captures stdout/stderr up to a configured
 * byte limit, validates JSON, and returns a
 * {@link HookEnvelopeAdapter.HandlerResult}.</p>
 */
public final class Runner {

    private static final Logger LOG = LoggerFactory.getLogger(Runner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Maximum retained bytes for each output stream. */
    public static final int MAX_HOOK_OUTPUT_BYTES = 100_000;
    private static final int READ_CHUNK_BYTES = 8_192;
    private static final int BLOCKING_EXIT_CODE = 2;

    private Runner() {}

    /**
     * Run one hook command with bounded time and captured output.
     */
    public static CompletableFuture<HookEnvelopeAdapter.HandlerResult> runCommandHandler(
            Snapshot.HookHandler handler, byte[] payload, Path cwd, double defaultTimeout,
            int maxOutputBytes, Map<String, String> env, String operationId,
            Presenter.HookProgressCallback onProgress) {
        if (handler.argv() != null) {
            if (handler.argv().isEmpty() || handler.argv().get(0).isBlank()) {
                return CompletableFuture.completedFuture(failure(handler.id(),
                        "invalid_command", "Hook argv is empty"));
            }
        } else if (handler.command().isBlank()) {
            return CompletableFuture.completedFuture(failure(handler.id(),
                    "invalid_command", "Hook command is empty"));
        }
        String resolvedCommand = handler.command();
        List<String> argv = handler.argv();
        Map<String, String> launchEnv = env == null ? Env.sanitizeHookEnviron(null) : env;
        double timeout = handler.timeout() != null ? handler.timeout() : defaultTimeout;
        String statusMessage = handler.statusMessage() == null ? "" : handler.statusMessage().trim();

        if (onProgress != null) {
            onProgress.call(new Presenter.HookProgress(operationId, handler.id(), handler.event(),
                    true, statusMessage));
        }

        ProcessBuilder builder;
        if (argv != null) {
            builder = new ProcessBuilder(argv);
        } else if (isWindows()) {
            builder = new ProcessBuilder("cmd.exe", "/c", resolvedCommand);
        } else {
            builder = new ProcessBuilder("sh", "-c", resolvedCommand);
        }
        builder.directory(cwd.toFile());
        builder.environment().clear();
        builder.environment().putAll(launchEnv);
        builder.redirectErrorStream(false);

        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            if (onProgress != null) {
                onProgress.call(new Presenter.HookProgress(operationId, handler.id(), handler.event(),
                        false, statusMessage));
            }
            return CompletableFuture.completedFuture(failure(handler.id(),
                    "launch_failed", "Could not launch hook: " + ex.getMessage()));
        }

        try (OutputStream stdin = process.getOutputStream()) {
            if (payload != null && payload.length > 0) {
                stdin.write(payload);
                stdin.flush();
            }
        } catch (IOException ex) {
            destroyForcibly(process);
            return CompletableFuture.completedFuture(failure(handler.id(),
                    "io_failed", "Hook communication failed: " + ex.getMessage()));
        }

        boolean finished;
        try {
            finished = process.waitFor((long) Math.ceil(timeout), TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            destroyForcibly(process);
            return CompletableFuture.completedFuture(failure(handler.id(),
                    "interrupted", "Hook execution interrupted"));
        }

        if (!finished) {
            destroyForcibly(process);
            if (onProgress != null) {
                onProgress.call(new Presenter.HookProgress(operationId, handler.id(), handler.event(),
                        false, statusMessage));
            }
            return CompletableFuture.completedFuture(failure(handler.id(),
                    "timeout", "Hook exceeded its " + timeout + " second timeout"));
        }

        OutputCapture result = captureOutput(process, maxOutputBytes);
        if (onProgress != null) {
            onProgress.call(new Presenter.HookProgress(operationId, handler.id(), handler.event(),
                    false, statusMessage));
        }
        List<HookDiagnostic> diagnostics = new ArrayList<>();
        if (result.stdoutTruncated) {
            diagnostics.add(diagnostic(handler.id(), "stdout_truncated",
                    "Hook stdout exceeded " + maxOutputBytes + " bytes"));
        }
        if (result.stderrTruncated) {
            diagnostics.add(diagnostic(handler.id(), "stderr_truncated",
                    "Hook stderr exceeded " + maxOutputBytes + " bytes"));
        }
        if (process.exitValue() == BLOCKING_EXIT_CODE) {
            String reason = decode(result.stderr).strip();
            if (reason.isEmpty()) reason = "Hook blocked the operation";
            HookWireOutput output = new HookWireOutput(true, null, null, null, false,
                    "block", reason, null);
            return CompletableFuture.completedFuture(
                    new HookEnvelopeAdapter.HandlerResult(handler.id(), output,
                            List.copyOf(diagnostics), null));
        }
        if (process.exitValue() != 0) {
            diagnostics.add(diagnostic(handler.id(), "nonzero_exit",
                    "Hook exited with status " + process.exitValue()));
            return CompletableFuture.completedFuture(
                    new HookEnvelopeAdapter.HandlerResult(handler.id(), null,
                            List.copyOf(diagnostics), null));
        }
        if (result.stdout.length == 0) {
            return CompletableFuture.completedFuture(
                    new HookEnvelopeAdapter.HandlerResult(handler.id(), null,
                            List.copyOf(diagnostics), null));
        }
        String plain = decode(result.stdout).strip();
        try {
            Object parsed = MAPPER.readValue(result.stdout, Object.class);
            HookWireOutput output = parseWireOutput(parsed);
            return CompletableFuture.completedFuture(
                    new HookEnvelopeAdapter.HandlerResult(handler.id(), output,
                            List.copyOf(diagnostics), null));
        } catch (Exception ex) {
            return CompletableFuture.completedFuture(
                    new HookEnvelopeAdapter.HandlerResult(handler.id(), null,
                            List.copyOf(diagnostics), plain));
        }
    }

    private static void destroyForcibly(Process process) {
        try {
            process.destroyForcibly();
        } catch (Exception ex) {
            LOG.debug("Failed to destroy hook process", ex);
        }
    }

    private static OutputCapture captureOutput(Process process, int limit) {
        byte[] stdout = drain(process.getInputStream(), limit);
        byte[] stderr = drain(process.getErrorStream(), limit);
        return new OutputCapture(stdout, stderr, false, false);
    }

    private static byte[] drain(InputStream stream, int limit) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[READ_CHUNK_BYTES];
        try {
            int read;
            while ((read = stream.read(chunk)) != -1) {
                if (out.size() < limit) {
                    int toWrite = Math.min(read, limit - out.size());
                    out.write(chunk, 0, toWrite);
                }
            }
        } catch (IOException ex) {
            LOG.debug("Hook stream read failed", ex);
        }
        return out.toByteArray();
    }

    private static String decode(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static HookWireOutput parseWireOutput(Object parsed) {
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Hook output must be a JSON object");
        }
        boolean cont = !map.containsKey("continue") || Boolean.TRUE.equals(map.get("continue"));
        Object stopReasonRaw = map.get("stopReason");
        String stopReason = stopReasonRaw == null ? null : stopReasonRaw.toString();
        Object systemMessageRaw = map.get("systemMessage");
        String systemMessage = systemMessageRaw == null ? null : systemMessageRaw.toString();
        Object terminalSequenceRaw = map.get("terminalSequence");
        String terminalSequence = terminalSequenceRaw == null ? null : terminalSequenceRaw.toString();
        boolean suppressOutput = Boolean.TRUE.equals(map.get("suppressOutput"));
        Object decisionRaw = map.get("decision");
        String decision = decisionRaw == null ? null : decisionRaw.toString();
        Object reasonRaw = map.get("reason");
        String reason = reasonRaw == null ? null : reasonRaw.toString();
        Object specificRaw = map.get("hookSpecificOutput");
        HookSpecificOutput specific = specificRaw == null ? null : parseSpecific(specificRaw);
        return new HookWireOutput(cont, stopReason, systemMessage, terminalSequence,
                suppressOutput, decision, reason, specific);
    }

    private static HookSpecificOutput parseSpecific(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        String hookEventName = String.valueOf(map.get("hookEventName"));
        return switch (hookEventName) {
            case "SessionStart" -> new WireTypes.SessionStartSpecificOutput(
                    hookEventName, stringOrNull(map.get("additionalContext")));
            case "UserPromptSubmit" -> new WireTypes.UserPromptSubmitSpecificOutput(
                    hookEventName, stringOrNull(map.get("additionalContext")),
                    Boolean.TRUE.equals(map.get("suppressOriginalPrompt")),
                    stringOrNull(map.get("sessionTitle")));
            case "PreToolUse" -> new WireTypes.PreToolUseSpecificOutput(
                    hookEventName, stringOrNull(map.get("additionalContext")),
                    stringOrNull(map.get("permissionDecision")),
                    stringOrNull(map.get("permissionDecisionReason")),
                    map.get("updatedInput") instanceof Map<?, ?> m ? toStringKeyed(m) : null);
            case "PermissionRequest" -> {
                Object decision = ((Map<?, ?>) map.get("decision"));
                PermissionDecision pd;
                if (decision instanceof Map<?, ?> dm) {
                    if ("allow".equals(dm.get("behavior"))) {
                        pd = new PermissionAllow(dm.get("updatedInput") != null);
                    } else {
                        pd = new PermissionDeny(stringOrNull(dm.get("message")),
                                Boolean.TRUE.equals(dm.get("interrupt")));
                    }
                } else {
                    pd = new PermissionDeny(null, false);
                }
                yield new WireTypes.PermissionRequestSpecificOutput(
                        hookEventName, pd, map.get("updatedInput") != null,
                        Boolean.TRUE.equals(map.get("updatedPermissions")));
            }
            case "PostToolUse" -> new WireTypes.PostToolUseSpecificOutput(
                    hookEventName, stringOrNull(map.get("additionalContext")),
                    map.get("updatedToolOutput"), map.get("updatedMCPToolOutput"));
            case "PostToolUseFailure" -> new WireTypes.PostToolUseFailureSpecificOutput(
                    hookEventName, stringOrNull(map.get("additionalContext")));
            case "Stop" -> new WireTypes.StopSpecificOutput(
                    hookEventName, stringOrNull(map.get("additionalContext")),
                    Boolean.TRUE.equals(map.get("continueLoop")));
            case "SubagentStart" -> new WireTypes.SubagentStartSpecificOutput(
                    hookEventName, stringOrNull(map.get("additionalContext")));
            case "SubagentStop" -> new WireTypes.SubagentStopSpecificOutput(
                    hookEventName, stringOrNull(map.get("additionalContext")));
            default -> null;
        };
    }

    private static Map<String, Object> toStringKeyed(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : source.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static HookEnvelopeAdapter.HandlerResult failure(String handlerId, String code, String message) {
        return new HookEnvelopeAdapter.HandlerResult(handlerId, null,
                List.of(diagnostic(handlerId, code, message)), null);
    }

    private static HookDiagnostic diagnostic(String handlerId, String code, String message) {
        return new HookDiagnostic(code, HookDiagnostic.Severity.WARNING, message, handlerId, null);
    }

    private record OutputCapture(byte[] stdout, byte[] stderr, boolean stdoutTruncated,
                                 boolean stderrTruncated) {}
}
