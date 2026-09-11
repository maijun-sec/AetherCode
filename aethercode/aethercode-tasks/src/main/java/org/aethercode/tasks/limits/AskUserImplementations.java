package org.aethercode.tasks.limits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * R240 (O-5): concrete {@link LimitsPausePolicy.AskUser} implementations
 * for the surfaces that haven't wired their own prompt yet.
 *
 * <p>Three flavours are provided:
 * <ul>
 *   <li>{@link #headless()} — the default for the {@code aethercode
 *       task ...} CLI. Prints the pause summary to stderr and reads
 *       one line of stdin (with a 5-second default timeout). If the
 *       user types {@code c}/{@code continue} → {@code CONTINUE};
 *       {@code r}/{@code raise} → {@code RAISE};
 *       anything else (including timeout / EOF) → {@code CANCEL}.
 *       This is intentionally conservative: in a long-running
 *       headless script a silent cap-bump could surprise the user.</li>
 *   <li>{@link #autoRaise()} — always returns {@link Decision#RAISE}.
 *       Equivalent to setting {@code AETHERCODE_LIMITS_ASK=raise} or
 *       for the typical CI / scripted use case where the user is OK
 *       with the cap being doubled.</li>
 *   <li>{@link #autoCancel()} — always returns {@link Decision#CANCEL}.
 *       Strict mode. Triggered by {@code AETHERCODE_LIMITS_ASK=cancel}.</li>
 * </ul>
 *
 * <p>Selection is centralized in {@link #fromEnv()} so call sites only
 * need to know one entry point. The fallback (no env var set) is
 * {@link #headless()}.
 *
 * <p>The class is intentionally tiny: it carries no state. Tests can
 * inject a custom {@link InputStream} via {@link #headless(InputStream, long)}
 * to assert the prompt → decision mapping without spawning a real
 * process.
 */
public final class AskUserImplementations {

    private static final Logger LOG = LoggerFactory.getLogger(AskUserImplementations.class);

    /** Env var that selects the {@link AskUser} variant. */
    public static final String ENV_LIMITS_ASK = "AETHERCODE_LIMITS_ASK";
    /** Optional headless stdin timeout in ms. Default 5_000. */
    public static final String ENV_LIMITS_TIMEOUT_MS = "AETHERCODE_LIMITS_TIMEOUT_MS";

    private AskUserImplementations() {}

    /**
     * Select the {@link AskUser} variant from the environment.
     * Recognised values: {@code headless} (default), {@code raise},
     * {@code cancel}. Unknown values fall back to {@link #headless()}.
     */
    public static LimitsPausePolicy.AskUser fromEnv() {
        String v = System.getenv(ENV_LIMITS_ASK);
        if (v == null || v.isBlank()) return headless();
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "raise": return autoRaise();
            case "cancel": return autoCancel();
            case "headless":
            default:
                return headless();
        }
    }

    /**
     * Default headless prompter. Reads from {@link System#in} with
     * the timeout from {@code AETHERCODE_LIMITS_TIMEOUT_MS} (default
     * 5_000 ms). The prompt is written to {@link System#err} so it
     * does not pollute the JSON-RPC wire on stdout.
     */
    public static LimitsPausePolicy.AskUser headless() {
        long timeoutMs = parseTimeoutMs(System.getenv(ENV_LIMITS_TIMEOUT_MS), 5_000L);
        return headless(System.in, timeoutMs);
    }

    /**
     * Test seam: headless prompter that reads from a given stream
     * with a fixed timeout.
     *
     * @param in        input stream (typically {@link System#in} or
     *                  a {@code ByteArrayInputStream} in tests)
     * @param timeoutMs read timeout in milliseconds; {@code <= 0}
     *                  disables the wait and immediately returns
     *                  {@link Decision#CANCEL}
     */
    public static LimitsPausePolicy.AskUser headless(InputStream in, long timeoutMs) {
        Objects.requireNonNull(in, "in");
        return prompt -> {
            String summary = renderSummary(prompt);
            System.err.println("[limits] child " + prompt.childId() + " hit a cap:");
            System.err.println(summary);
            System.err.print("[limits] (c)ontinue / (r)aise / anything-else=cancel : ");
            System.err.flush();
            if (timeoutMs <= 0) {
                return LimitsPausePolicy.Decision.CANCEL;
            }
            String line = readOneLine(in, timeoutMs);
            if (line == null) {
                System.err.println("[limits] timeout/EOF — cancelling");
                return LimitsPausePolicy.Decision.CANCEL;
            }
            String t = line.trim().toLowerCase(Locale.ROOT);
            if (t.equals("c") || t.equals("continue")) {
                return LimitsPausePolicy.Decision.CONTINUE;
            }
            if (t.equals("r") || t.equals("raise")) {
                return LimitsPausePolicy.Decision.RAISE;
            }
            System.err.println("[limits] unrecognised input — cancelling");
            return LimitsPausePolicy.Decision.CANCEL;
        };
    }

    /**
     * Always raise. Logs at INFO so an operator can later audit
     * "the cap was doubled, why?" by grepping the daemon log.
     */
    public static LimitsPausePolicy.AskUser autoRaise() {
        return prompt -> {
            String trips = prompt.tripped().stream()
                    .map(h -> h.name() + "=" + h.actual() + "/" + h.limit())
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            LOG.info("AETHERCODE_LIMITS_ASK=raise — auto-raising caps for child {} ({})",
                    prompt.childId(), trips);
            return LimitsPausePolicy.Decision.RAISE;
        };
    }

    /**
     * Always cancel. Logs at WARN because cancellation is the
     * operator-visible "something went wrong" path; CI users
     * grepping the log want this to surface.
     */
    public static LimitsPausePolicy.AskUser autoCancel() {
        return prompt -> {
            String trips = prompt.tripped().stream()
                    .map(h -> h.name() + "=" + h.actual() + "/" + h.limit())
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            LOG.warn("AETHERCODE_LIMITS_ASK=cancel — auto-cancelling child {} ({})",
                    prompt.childId(), trips);
            return LimitsPausePolicy.Decision.CANCEL;
        };
    }

    /**
     * Render the pause prompt as a 4-line, human-readable summary
     * suitable for the stderr prompt. Kept here (not in
     * {@link LimitsPausePolicy}) so the formatting can be tweaked
     * without touching the lifecycle glue.
     */
    static String renderSummary(LimitsPausePolicy.PausePrompt p) {
        StringBuilder sb = new StringBuilder();
        sb.append("  tripped: ");
        for (int i = 0; i < p.tripped().size(); i++) {
            if (i > 0) sb.append(", ");
            LimitsEnforcer.LimitHit h = p.tripped().get(i);
            sb.append(h.name()).append('=').append(h.actual()).append('/').append(h.limit());
        }
        sb.append('\n');
        sb.append("  current limits: ").append(p.currentLimits().toMap()).append('\n');
        sb.append("  cwd: ").append(p.cwd() == null ? "" : p.cwd()).append('\n');
        sb.append("  prompt: ").append(p.promptExcerpt() == null ? "" : p.promptExcerpt());
        return sb.toString();
    }

    /**
     * Read one line from {@code in} within {@code timeoutMs}.
     * Returns {@code null} on timeout, EOF, or IO error. The
     * implementation spawns a single reader thread and joins it
     * with a deadline; the thread is left as a daemon so a stalled
     * stdin cannot keep the JVM alive.
     */
    static String readOneLine(InputStream in, long timeoutMs) {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        final String[] holder = new String[1];
        final IOException[] failure = new IOException[1];
        Thread t = new Thread(() -> {
            try {
                holder[0] = reader.readLine();
            } catch (IOException e) {
                failure[0] = e;
            }
        }, "askuser-readline");
        t.setDaemon(true);
        t.start();
        try {
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (t.isAlive()) {
                if (System.nanoTime() >= deadlineNanos) {
                    // Don't interrupt — the read may be blocking
                    // uninterruptibly. Just return null and let
                    // the daemon thread continue in the background.
                    return null;
                }
                Thread.sleep(20L);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (failure[0] != null) {
            LOG.debug("askuser read failed: {}", failure[0].getMessage());
            return null;
        }
        return holder[0];
    }

    static long parseTimeoutMs(String raw, long fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            long v = Long.parseLong(raw.trim());
            return v < 0 ? fallback : v;
        } catch (NumberFormatException nfe) {
            return fallback;
        }
    }

    // Exposed for tests so the summary format has a stable reference.
    static Map<String, Object> exampleLimits() {
        return Limits.builder()
                .wallClockMs(600_000L)
                .tokens(50_000L)
                .calls(200L)
                .fileWrites(50L)
                .network(100L)
                .build()
                .toMap();
    }
}
