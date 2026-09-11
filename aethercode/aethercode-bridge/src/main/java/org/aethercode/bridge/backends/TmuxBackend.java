package org.aethercode.bridge.backends;

import org.aethercode.bridge.SwarmCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * tmux-backed swarm. Each teammate runs in its own tmux window. The bridge
 * sends tasks by writing to the window's pane and reads replies by tailing a
 * per-teammate output file.
 *
 * <p>Why tmux? The user can attach to any teammate's window with {@code tmux
 * attach -t aethercode-<id>} and watch the agent work. The bridge stays out of
 * the way.
 */
public class TmuxBackend implements TeammateBackend {

    private static final Logger LOG = LoggerFactory.getLogger(TmuxBackend.class);

    private final Map<String, String> windowFor = new ConcurrentHashMap<>();
    private final Map<String, String> outputFileFor = new ConcurrentHashMap<>();
    private final Map<String, Long> offsetFor = new ConcurrentHashMap<>();
    private final String sessionName;

    public TmuxBackend() { this("aethercode"); }
    public TmuxBackend(String sessionName) { this.sessionName = sessionName; }

    public String name() { return "tmux"; }

    public String spawn(SwarmCoordinator.TeammateSpec spec) throws Exception {
        String id = spec.id() == null ? UUID.randomUUID().toString().substring(0, 8) : spec.id();
        String win = sessionName + "-" + id;
        String out = "/tmp/aethercode-" + id + ".out";
        // Ensure the session exists
        run("tmux", "new-session", "-d", "-s", sessionName, "-n", win);
        run("tmux", "send-keys", "-t", win + ".0",
                "aethercode-cli --cwd " + safeCwd() + " --print " + shQuote("$INBOX") + " > " + out + " 2>&1", "Enter");
        windowFor.put(id, win);
        outputFileFor.put(id, out);
        offsetFor.put(id, 0L);
        LOG.info("tmux backend: spawned teammate {} in window {}", id, win);
        return id;
    }

    public CompletableFuture<String> sendTask(String teammateId, String task) {
        String win = windowFor.get(teammateId);
        if (win == null) return CompletableFuture.failedFuture(new IllegalArgumentException("unknown teammate: " + teammateId));
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Send the task as input — the aethercode-cli in the window reads stdin
                run("tmux", "send-keys", "-t", win + ".0", shQuote(task), "Enter");
                // Wait for output to grow, then read.
                String out = outputFileFor.get(teammateId);
                long lastSize = offsetFor.get(teammateId);
                for (int i = 0; i < 60; i++) {
                    java.io.File f = new java.io.File(out);
                    if (f.length() > lastSize) {
                        Thread.sleep(500);
                        String content = readFrom(out, lastSize);
                        offsetFor.put(teammateId, f.length());
                        if (!content.trim().isEmpty()) return content;
                    }
                    Thread.sleep(500);
                }
                return "(timed out waiting for output)";
            } catch (Exception e) {
                return "tmux send failed: " + e.getMessage();
            }
        });
    }

    public void retire(String teammateId) {
        String win = windowFor.remove(teammateId);
        if (win != null) {
            try { run("tmux", "kill-window", "-t", win); } catch (Exception ignored) {}
        }
        outputFileFor.remove(teammateId);
        offsetFor.remove(teammateId);
    }

    private static String safeCwd() {
        try { return java.nio.file.Path.of("").toAbsolutePath().toString(); }
        catch (Exception e) { return "/tmp"; }
    }

    private static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void run(String... args) throws java.io.IOException, InterruptedException {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
    }

    private static String readFrom(String file, long offset) throws java.io.IOException {
        try (var raf = new java.io.RandomAccessFile(file, "r")) {
            raf.seek(offset);
            byte[] buf = new byte[(int) (raf.length() - offset)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }
}
