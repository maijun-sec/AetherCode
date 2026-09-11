package org.aethercode.code.hooks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight hook dispatch for external tool integration.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.legacy} module. The legacy hook system
 * is retained for backward compatibility until
 * {@value Loading#LEGACY_HOOKS_REMOVAL_DATE}. Hook configuration is
 * loaded from {@code ~/.deepagents/hooks.json} and matching commands
 * receive JSON payloads on stdin.</p>
 *
 * <p>Subprocess work is offloaded to a thread pool so the caller's
 * event loop is never stalled. Failures are logged but never bubble
 * up to the caller.</p>
 */
public final class Legacy {

    private static final Logger LOG = LoggerFactory.getLogger(Legacy.class);

    /** Max characters of {@code tool_output} included in {@code tool.result} hook payloads. */
    public static final int HOOK_TOOL_OUTPUT_LIMIT = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private static volatile List<Map<String, Object>> cachedConfig;
    private static final java.util.Set<CompletableFuture<Void>> BACKGROUND_TASKS =
            ConcurrentHashMap.newKeySet();

    private Legacy() {}

    /** Load and cache hook definitions from the config file. */
    public static synchronized List<Map<String, Object>> loadHooks() {
        if (cachedConfig != null) return cachedConfig;
        Path hooksPath = Path.of(System.getProperty("user.home", "~"),
                ".deepagents", "hooks.json");
        if (!Files.isRegularFile(hooksPath)) {
            cachedConfig = List.of();
            return cachedConfig;
        }
        try {
            Object parsed = MAPPER.readValue(Files.readAllBytes(hooksPath), Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                LOG.warn("Hooks config at {} must be a JSON object, got {}",
                        hooksPath, parsed == null ? "null" : parsed.getClass().getSimpleName());
                cachedConfig = List.of();
                return cachedConfig;
            }
            Object hooks = map.get("hooks");
            if (!(hooks instanceof List<?> list)) {
                LOG.warn("Hooks config 'hooks' key at {} must be a list, got {}",
                        hooksPath, hooks == null ? "null" : hooks.getClass().getSimpleName());
                cachedConfig = List.of();
                return cachedConfig;
            }
            List<Map<String, Object>> converted = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> im) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : im.entrySet()) {
                        copy.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    converted.add(copy);
                }
            }
            cachedConfig = List.copyOf(converted);
            return cachedConfig;
        } catch (IOException ex) {
            LOG.warn("Failed to load hooks config from {}: {}", hooksPath, ex.getMessage());
            cachedConfig = List.of();
            return cachedConfig;
        }
    }

    /** Clear the cached config (used by tests). */
    public static synchronized void resetCache() {
        cachedConfig = null;
    }

    /**
     * Fire matching hook commands with {@code payload} serialized as
     * JSON on stdin.
     */
    public static CompletableFuture<Void> dispatchHook(String event, Map<String, Object> payload) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<Map<String, Object>> hooks = loadHooks();
                if (hooks.isEmpty()) return;
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("event", event);
                if (payload != null) envelope.putAll(payload);
                byte[] bytes = MAPPER.writeValueAsBytes(envelope);
                dispatchSync(event, bytes, hooks);
            } catch (Throwable ex) {
                LOG.warn("Unexpected error in dispatchHook for event {}", event, ex);
            }
        });
    }

    /**
     * Schedule {@link #dispatchHook(String, Map)} as a background
     * task with a strong reference.
     */
    public static void dispatchHookFireAndForget(String event, Map<String, Object> payload) {
        CompletableFuture<Void> task = dispatchHook(event, payload);
        BACKGROUND_TASKS.add(task);
        task.whenComplete((v, ex) -> BACKGROUND_TASKS.remove(task));
    }

    /** Return whether fire-and-forget hook tasks are still in flight. */
    public static boolean hasPendingHooks() {
        return BACKGROUND_TASKS.stream().anyMatch(t -> !t.isDone());
    }

    /** Await all in-flight fire-and-forget hook tasks. */
    public static CompletableFuture<Void> drainPendingHooks() {
        List<CompletableFuture<Void>> snapshot = new ArrayList<>(BACKGROUND_TASKS);
        if (snapshot.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.allOf(snapshot.toArray(new CompletableFuture[0]));
    }

    private static void dispatchSync(String event, byte[] payloadBytes,
                                     List<Map<String, Object>> hooks) {
        List<List<String>> matching = new ArrayList<>();
        for (Map<String, Object> hook : hooks) {
            Object command = hook.get("command");
            if (!(command instanceof List<?> argv) || argv.isEmpty()) {
                LOG.warn("Skipping hook with invalid `command` for event {}: {}",
                        event, command);
                continue;
            }
            List<String> argvStrings = new ArrayList<>();
            boolean valid = true;
            for (Object part : argv) {
                if (!(part instanceof String s)) {
                    valid = false;
                    break;
                }
                argvStrings.add(s);
            }
            if (!valid) {
                LOG.warn("Skipping hook with invalid `command` for event {}: {}", event, command);
                continue;
            }
            Object events = hook.get("events");
            if (events instanceof List<?> list && !list.isEmpty()) {
                if (!list.contains(event)) continue;
            }
            matching.add(argvStrings);
        }
        if (matching.isEmpty()) return;
        if (matching.size() == 1) {
            runSingle(matching.get(0), event, payloadBytes);
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(matching.size());
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (List<String> cmd : matching) {
                futures.add(pool.submit(() -> runSingle(cmd, event, payloadBytes)));
            }
            for (java.util.concurrent.Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception ex) {
                    LOG.debug("Hook dispatch future failed", ex);
                }
            }
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination((long) Math.ceil(Env.HOOK_SUBPROCESS_TIMEOUT), TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void runSingle(List<String> command, String event, byte[] payloadBytes) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(payloadBytes);
                stdin.flush();
            }
            boolean finished = process.waitFor((long) Math.ceil(Env.HOOK_SUBPROCESS_TIMEOUT),
                    TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.warn("Hook command timed out (>{}s) for event {}: {}",
                        Env.HOOK_SUBPROCESS_TIMEOUT, event, command);
            }
        } catch (IOException ex) {
            LOG.warn("Hook command failed for event {}: {} — {}",
                    event, command, ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            LOG.warn("Hook dispatch failed unexpectedly for event {}: {}",
                    event, command, ex);
        }
    }
}
