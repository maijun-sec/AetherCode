package org.aethercode.deepagents.selfimprove;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.deepagents.middleware.Middleware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * R243.2 (O-3): wire the ReasoningBank + reflection / recall
 * middlewares into a long-running runtime such as
 * {@code aethercode-talon}. Bundles the default
 * configuration (file-backed bank, 7-day half-life decay,
 * 1000-unit LRU cap, {@link SelfReflectMiddleware},
 * {@link SuccessReflectMiddleware},
 * {@link BankRecallMiddleware}) into a single call so a
 * host only has to feed in a few knobs and pass the
 * resulting list to {@code CreateDeepAgent.create(...)} /
 * {@code CreateDeepAgent.createDeepAgent(...)}.
 *
 * <h2>Why a dedicated wiring class</h2>
 *
 * <p>Without this helper, every host has to know the exact
 * recipe: file-backed storage, exponential decay, LRU
 * growth policy, ChatClientReflector wrapping Spring AI's
 * default client, maxSuccessesPerTurn=1, etc. The recipe
 * is opinionated; centralising it here means a host
 * running the latest AetherCode always gets the latest
 * "best practice" without re-implementing it.
 *
 * <h2>Fail-safe</h2>
 *
 * <p>{@link #build(Path, ChatClient, Map)} never throws.
 * If the {@link ChatClient} is {@code null} (e.g. host has
 * not configured an LLM), it falls back to a
 * {@link StubReflector} so the bank still gets written
 * with whatever the parse pipeline extracts. The host can
 * always swap in a real reflector later without touching
 * the rest of the wiring.
 *
 * <h2>Env knobs (all optional)</h2>
 *
 * <ul>
 *   <li>{@code DEEPAGENTS_TALON_SELFREFLECT=false} —
 *       opt out of all O-3 wiring; returns an empty list
 *       and a no-op bank.</li>
 *   <li>{@code DEEPAGENTS_TALON_BANK_DECAY_DAYS=N} —
 *       exponential half-life in days; default 7.</li>
 *   <li>{@code DEEPAGENTS_TALON_BANK_MAX=N} — LRU cap
 *       (number of units kept on disk); default 1000.</li>
 * </ul>
 *
 * <h2>Bank path</h2>
 *
 * <p>Default: {@code {assistantDir}/.aethercode/reasoning-bank/}.
 * If {@code assistantDir} is {@code null} or unusable, the
 * wiring falls back to an in-memory bank so the runtime
 * still starts. The host should log a warning in that
 * case (the {@code Result} carries the resolved path so
 * the host can surface it).
 */
public final class TalonSelfReflectWiring {

    private static final Logger LOG = LoggerFactory.getLogger(TalonSelfReflectWiring.class);

    /** Opt-out env var; set to {@code "false"} to disable
     *  the entire O-3 wiring. */
    public static final String ENV_OPT_OUT = "DEEPAGENTS_TALON_SELFREFLECT";

    /** Half-life in days for the exponential decay policy. */
    public static final String ENV_DECAY_DAYS = "DEEPAGENTS_TALON_BANK_DECAY_DAYS";

    /** LRU cap on the bank (max units kept). */
    public static final String ENV_BANK_MAX = "DEEPAGENTS_TALON_BANK_MAX";

    /** R245.3: how often to fire {@link ReasoningBank#decayPass}
     *  in the background. Default 60 minutes; set to
     *  {@code "0"} to disable the scheduler. */
    public static final String ENV_DECAY_INTERVAL_MIN = "DEEPAGENTS_TALON_DECAY_INTERVAL_MIN";

    /** bearer token required by {@code /bank/*} when
     *  the daemon is exposed to a non-localhost network.
     *  Default: unset (server is unauthenticated, R244.2
     *  default). */
    public static final String ENV_BANK_TOKEN = "AETHERCODE_BANK_TOKEN";

    /** PKCS#12 keystore path for HTTPS. When unset,
     *  the server binds plain HTTP (R244.2 default). When
     *  set, the server binds HTTPS and loads the keystore
     *  via {@code KeyStore.getInstance("PKCS12")}. */
    public static final String ENV_BANK_TLS_KEYSTORE = "AETHERCODE_BANK_TLS_KEYSTORE";

    /** password for the PKCS#12 keystore. May be
     *  empty for unencrypted keystores. */
    public static final String ENV_BANK_TLS_PASS = "AETHERCODE_BANK_TLS_PASS";

    public static final int DEFAULT_MAX_SUCCESSES_PER_TURN = 1;
    public static final long DEFAULT_DECAY_DAYS = 7L;
    public static final int DEFAULT_BANK_MAX = 1000;
    /** R245.3: default decay-pass interval in minutes. */
    public static final long DEFAULT_DECAY_INTERVAL_MIN = 60L;

    private TalonSelfReflectWiring() {}

    /**
     * The outcome of {@link #build}. Hosts should pass
     * {@link #middlewares} to the deep-agent factory and
     * may want to keep {@link #bank} around to expose
     * (e.g. via a CLI command).
     */
    public record Result(
            List<Middleware> middlewares,
            ReasoningBank bank,
            Path bankDir,
            boolean enabled,
            String reason) {
    }

    /**
     * Build the wiring. Never throws.
     *
     * @param assistantDir the runtime's assistant
     *        directory (used as the parent of the bank
     *        directory). May be {@code null} — wiring
     *        degrades to an in-memory bank.
     * @param chatClient the host's chat client (used as
     *        the reflection LLM). May be {@code null} —
     *        wiring falls back to {@link StubReflector}.
     * @param env the host's effective environment, used to
     *        read the O-3 env knobs. May be {@code null}.
     */
    public static Result build(Path assistantDir, ChatClient chatClient, Map<String, String> env) {
        Map<String, String> e = env == null ? Map.of() : env;

        if (isOptedOut(e)) {
            LOG.info("self-reflect wiring disabled ({}={})",
                    ENV_OPT_OUT, e.get(ENV_OPT_OUT));
            return new Result(List.of(),
                    new ReasoningBank(), null, false,
                    "opt-out env var set");
        }

        // Resolve the bank directory. A null or unusable
        // assistantDir degrades to in-memory; the host can
        // surface the path via the result.
        Path bankDir = resolveBankDir(assistantDir);
        ReasoningBank bank;
        if (bankDir == null) {
            LOG.warn("self-reflect wiring: no usable assistantDir; falling back to in-memory bank");
            bank = new ReasoningBank();
        } else {
            try {
                Files.createDirectories(bankDir);
                long decayDays = readLong(e, ENV_DECAY_DAYS, DEFAULT_DECAY_DAYS);
                int bankMax = (int) readLong(e, ENV_BANK_MAX, DEFAULT_BANK_MAX);
                if (decayDays <= 0) {
                    LOG.warn("self-reflect wiring: {}={} is non-positive, falling back to {}",
                            ENV_DECAY_DAYS, decayDays, DEFAULT_DECAY_DAYS);
                    decayDays = DEFAULT_DECAY_DAYS;
                }
                if (bankMax <= 0) {
                    LOG.warn("self-reflect wiring: {}={} is non-positive, falling back to {}",
                            ENV_BANK_MAX, bankMax, DEFAULT_BANK_MAX);
                    bankMax = DEFAULT_BANK_MAX;
                }
                bank = ReasoningBank.withFileStorage(
                        bankDir,
                        UtilityDecay.exponential(Duration.ofDays(decayDays)),
                        new LruEviction(bankMax));
                LOG.info("self-reflect wiring enabled: bankDir={} decayDays={} bankMax={}",
                        bankDir, decayDays, bankMax);
            } catch (Exception re) {
                LOG.warn("self-reflect wiring: failed to initialise bank at {}: {}",
                        bankDir, re.getMessage());
                bank = new ReasoningBank();
                bankDir = null;
            }
        }

        // Resolve the reflector. Null ChatClient is
        // acceptable; we fall back to a no-op stub so the
        // bank still gets written (parse will store empty
        // units if the model never speaks).
        Reflector reflector = resolveReflector(chatClient);

        // Build the middlewares. Success reflection is
        // opt-out via maxSuccessesPerTurn=0 if a host
        // wants failure-only.
        List<Middleware> middlewares = new ArrayList<>(4);
        middlewares.add(new SelfReflectMiddleware(reflector, bank));
        middlewares.add(new SuccessReflectMiddleware(reflector, bank,
                SuccessClassifier.always(), null,
                DEFAULT_MAX_SUCCESSES_PER_TURN));
        BankRecallMiddleware recallMw = new BankRecallMiddleware(bank);
        middlewares.add(recallMw);
        // R244.1 (O-6): self-eval middleware writes the
        // "did the strategy actually work?" outcome back
        // to the bank on every tool call. The recall
        // middleware it consults is the one we just
        // built, so the confidence feedback loop is
        // closed at construction time.
        middlewares.add(new SelfEvalMiddleware(bank, recallMw));
        return new Result(List.copyOf(middlewares), bank, bankDir, true, "ok");
    }

    /** Convenience overload with a null env. */
    public static Result build(Path assistantDir, ChatClient chatClient) {
        return build(assistantDir, chatClient, null);
    }

    /**
     * R243.3 (O-3): run {@link Drift#runOnce} against the
     * bank carried by {@code wiring}, with a sensible
     * default of {@code assistantDir/AGENTS.md} as the
     * drift target. Convenience for hosts that want
     * one-line DRIFT wiring next to the rest of O-3.
     *
     * <p>Returns a no-op result if the wiring is not
     * enabled (e.g. opt-out env var set).
     */
    public static DriftResult driftOnce(Result wiring, Path agentsMdPath, DriftConfig driftConfig) {
        if (wiring == null || !wiring.enabled() || wiring.bank() == null) {
            return DriftResult.noop(0, 0, agentsMdPath == null ? null : agentsMdPath.toString());
        }
        return Drift.runOnce(wiring.bank(), agentsMdPath,
                driftConfig == null ? DriftConfig.defaults() : driftConfig);
    }

    /**
     * R244.2 (O-10): start a {@link BankServer} bound to
     * the bank carried by {@code wiring}. The server
     * exposes a small HTTP surface
     * (see {@link BankServer}) so a non-JVM surface (TUI /
     * desktop / IntelliJ) can read and write the same
     * strategy library. Returns the started server; the
     * caller is responsible for {@link BankServer#stop()}.
     *
     * <p>Opt-in: not auto-started by {@link #build} so
     * existing hosts are not surprised by a new
     * localhost listener. Pass
     * {@code DEEPAGENTS_TALON_BANK_PORT} (default
     * {@link BankServer#DEFAULT_PORT}) to choose a port.</p>
     *
     * <p>R247: read {@link #ENV_BANK_TOKEN} from the
     * supplied {@code env} and wire it into the server.
     * When the var is unset (or blank), the server is
     * unauthenticated (R244.2 default). When set, every
     * {@code /bank/*} request must carry
     * {@code Authorization: Bearer <token>}; {@code /healthz}
     * is always open.</p>
     *
     * <p>R248: if {@link #ENV_BANK_TLS_KEYSTORE} is set,
     * bind HTTPS instead of HTTP, loading the keystore at
     * the supplied path. {@link #ENV_BANK_TLS_PASS}
     * optionally carries the keystore password. When
     * both are unset, the server binds plain HTTP (R244.2
     * default). Production hosts that expose the bank to
     * a non-localhost network should set both R247
     * (token) and R248 (TLS) for defence in depth.</p>
     */
    public static BankServer startBankServer(Result wiring, int port, Map<String, String> env) {
        if (wiring == null || wiring.bank() == null) {
            throw new IllegalStateException("wiring / bank must be non-null");
        }
        Map<String, String> e = env == null ? Map.of() : env;
        String token = e.get(ENV_BANK_TOKEN);
        String keystore = e.get(ENV_BANK_TLS_KEYSTORE);
        BankServer server = new BankServer(wiring.bank(), token);
        if (keystore != null && !keystore.isBlank()) {
            String pass = e.getOrDefault(ENV_BANK_TLS_PASS, "");
            return server.startTLS(port, keystore, pass);
        }
        return server.start(port);
    }

    /**
     * R244.2 (O-10) + R247: convenience overload with a
     * null env (no auth). Equivalent to the prior round.2
     * behaviour.
     */
    public static BankServer startBankServer(Result wiring, int port) {
        return startBankServer(wiring, port, null);
    }

    /** R244.2 (O-10): start a {@link BankServer} on the
     *  default port (7777). */
    public static BankServer startBankServer(Result wiring) {
        return startBankServer(wiring, BankServer.DEFAULT_PORT);
    }

    /**
     * R245.3 (O-3): start a {@link DecayScheduler} that
     * periodically fires {@link ReasoningBank#decayPass}.
     * The interval is read from {@link #ENV_DECAY_INTERVAL_MIN}
     * (default {@link #DEFAULT_DECAY_INTERVAL_MIN} = 60 min);
     * set to {@code "0"} or a negative number to disable.
     *
     * <p>Returns {@code null} if the wiring is not enabled
     * or the env knob is set to 0/negative — the host can
     * simply ignore the return value without special-casing.
     * The host is responsible for {@link DecayScheduler#stop()}
     * (typically in a shutdown hook).</p>
     */
    public static DecayScheduler startDecayScheduler(Result wiring, Map<String, String> env) {
        if (wiring == null || !wiring.enabled() || wiring.bank() == null) {
            return null;
        }
        Map<String, String> e = env == null ? Map.of() : env;
        long minutes = readLong(e, ENV_DECAY_INTERVAL_MIN, DEFAULT_DECAY_INTERVAL_MIN);
        if (minutes <= 0) {
            LOG.info("self-reflect decay scheduler disabled ({}={})",
                    ENV_DECAY_INTERVAL_MIN, e.get(ENV_DECAY_INTERVAL_MIN));
            return null;
        }
        DecayScheduler scheduler = DecayScheduler.start(
                wiring.bank(), Duration.ofMinutes(minutes));
        LOG.info("self-reflect decay scheduler started: interval={} min", minutes);
        return scheduler;
    }

    /** Convenience overload with a null env. */
    public static DecayScheduler startDecayScheduler(Result wiring) {
        return startDecayScheduler(wiring, null);
    }

    private static boolean isOptedOut(Map<String, String> env) {
        String v = env.get(ENV_OPT_OUT);
        if (v == null || v.isBlank()) return false;
        String norm = v.trim().toLowerCase();
        return norm.equals("false") || norm.equals("0") || norm.equals("no") || norm.equals("off");
    }

    private static Path resolveBankDir(Path assistantDir) {
        if (assistantDir == null) return null;
        try {
            Path dir = assistantDir.resolve(".aethercode").resolve("reasoning-bank");
            // Test writability by ensuring the directory
            // chain exists. If assistantDir itself does
            // not exist, we still try; the file storage
            // createDirectories call will fail with a
            // clear error.
            Files.createDirectories(assistantDir);
            return dir;
        } catch (Exception e) {
            LOG.warn("self-reflect wiring: cannot resolve bankDir under {}: {}",
                    assistantDir, e.getMessage());
            return null;
        }
    }

    private static Reflector resolveReflector(ChatClient chatClient) {
        if (chatClient == null) {
            LOG.warn("self-reflect wiring: no ChatClient; using StubReflector (no-op)");
            return new StubReflector();
        }
        try {
            return new ChatClientReflector(chatClient);
        } catch (RuntimeException e) {
            LOG.warn("self-reflect wiring: failed to build ChatClientReflector: {}", e.getMessage());
            return new StubReflector();
        }
    }

    private static long readLong(Map<String, String> env, String key, long dflt) {
        String v = env.get(key);
        if (v == null || v.isBlank()) return dflt;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            LOG.warn("self-reflect wiring: {}={} is not a number, falling back to {}",
                    key, v, dflt);
            return dflt;
        }
    }
}
