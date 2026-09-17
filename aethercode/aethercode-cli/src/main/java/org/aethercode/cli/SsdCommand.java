package org.aethercode.cli;

import org.aethercode.core.stream.StreamEvent;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.workflows.ssd.InteractiveRepl;
import org.aethercode.workflows.ssd.SsdConfig;
import org.aethercode.workflows.ssd.SsdConfig.Phase;
import org.aethercode.workflows.ssd.SsdRunner;
import org.aethercode.workflows.ssd.SsdRunner.AbortException;
import org.aethercode.workflows.ssd.SsdRunner.LlmFn;
import org.aethercode.workflows.ssd.SsdRunner.Logger;
import org.aethercode.workflows.ssd.SsdRunner.PhaseResult;
import org.aethercode.workflows.ssd.SsdRunner.ReplFn;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * prior round — the {@code aethercode ssd} CLI surface. Replaces the
 * legacy external {@code python ssd.py <port> ...} driver; the SSD
 * pipeline is now an in-process built-in.
 *
 * <h2>Two modes</h2>
 * <p>The command has two mutually-exclusive modes:
 * <ol>
 *   <li><b>Run mode (default)</b>: {@code aethercode ssd <feature> "..."}
 *       — runs the 4-phase SSD pipeline. Supports
 *       {@code --from-phase}, {@code --to-phase}, {@code --force},
 *       {@code --auto}. This is the R236 behaviour.</li>
 *   <li><b>Utility mode</b>: {@code aethercode ssd --show},
 *       {@code --validate}, or {@code aethercode ssd --clean <file>}.
 *       These do not require {@code <feature>} or an intent; they
 *       operate on the resolved config or a file on disk. R237 added
 *       them so operators can inspect the resolved config and
 *       debug the cleanOutput pass without running a full SSD.</li>
 * </ol>
 *
 * <h2>Why a CLI command (not a daemon RPC)</h2>
 * <p>The SSD runner is interactive (per-phase REPL) and only needs
 * a single LLM call per phase. Spawning a daemon just to drive it
 * added a network round-trip + a notification-polling layer (see
 * R235's three bug fixes around {@code _notify_baseline},
 * {@code runId} pin, and the {@code last_tool_idx} post-tool filter)
 * with no benefit. Going in-process lets us use
 * {@link AetherCodeEngine#query(String)} directly and consume the
 * {@code Stream<StreamEvent>} with a simple forEach.
 *
 * <h2>Why a CLI command (not a workflow YAML)</h2>
 * <p>The workflow engine's executor is a single-pass spawner with
 * no human-in-the-loop gate step. Adding a {@code gate} step type
 * is a much larger change than a focused orchestrator. SSD's
 * gate is "ask the user for revisions; loop on revision" — the
 * loop semantics are not a good fit for a step-based workflow.
 * Treating SSD as a built-in command in the CLI keeps the
 * workflow YAML surface clean.
 */
@Command(
        name = "ssd",
        mixinStandardHelpOptions = true,
        description = {
                "对应历史 round: Run the SSD (Spec-Design-Tasks-Dev) 4-phase workflow against",
                "an in-process AetherCodeEngine. Each phase produces a markdown",
                "artefact under <cwd>/.aethercode/ssd/<feature>/; you confirm or",
                "paste revisions between phases.",
                "",
                "Replaces the legacy `python ssd.py <port> ...` external driver.",
                "",
                "Two modes:",
                "  default:  ssd <feature> <intent...>   (run 4-phase pipeline)",
                "  utility:  ssd --show                   (dump resolved config)",
                "            ssd --validate               (validate ssd.yaml + print summary)",
                "            ssd --clean <file>           (run SsdRunner.cleanOutput on <file>)"
        }
)
public class SsdCommand implements Callable<Integer> {

    @Parameters(arity = "0..1", description = "Feature name (kebab-case). The artefact directory is <cwd>/.aethercode/ssd/<feature>/. Required for run mode; ignored in utility mode.")
    String feature;

    @Parameters(arity = "0..*", description = "Free-form description of the change. Becomes the seed for spec.md. Required for run mode; ignored in utility mode.")
    String[] intentTokens = new String[0];

    @Option(names = {"--cwd"},
            description = "Project cwd (default: $user.dir).")
    Path cwd = Path.of("").toAbsolutePath();

    @Option(names = {"--force"},
            description = "Run mode: regenerate even when the artefact already exists.")
    boolean force;

    @Option(names = {"--auto", "-y"},
            description = "Run mode: skip the stdin confirmation REPL (auto-accept every phase).")
    boolean auto;

    @Option(names = {"--interactive", "-i"},
            description = "R281: run mode: emit newline-delimited JSON events to stdout and read JSON commands from stdin. Lets a UI (desktop SsdPanel, TUI dashboard) drive the per-phase confirmation flow instead of typing into a terminal. Mutually exclusive with --auto.")
    boolean interactive;

    @Option(names = {"--from-phase"},
            description = "Run mode: start at phase N (1..4). Earlier artefacts are reused. Default: 1.")
    int fromPhase = 1;

    @Option(names = {"--to-phase"},
            description = "R237: run mode: stop after phase N (1..4). Default: 4. Useful for e2e demos that don't have time for the full dev phase (~25 LLM calls).")
    int toPhase = 4;

    @Option(names = {"--show"},
            description = "Utility mode: print the resolved SSD config (source, 4 phase summaries) and exit. Does NOT touch the LLM.")
    boolean show;

    @Option(names = {"--validate"},
            description = "Utility mode: validate the resolved SSD config (same checks SsdConfig.fromProjectOrBundled runs internally) and print a per-phase report. Does NOT touch the LLM.")
    boolean validate;

    @Option(names = {"--clean"},
            description = "Utility mode: read <arg>, run SsdRunner.cleanOutput, write to stdout. Useful for testing cleanOutput against real LLM output without running a full SSD.")
    Path cleanTarget;

    @Override
    public Integer call() {
        // ----- Mode dispatch -----
        // The three utility flags are mutually exclusive (only one
        // makes sense; multiple would be ambiguous).
        int utilCount = (show ? 1 : 0) + (validate ? 1 : 0) + (cleanTarget != null ? 1 : 0);
        if (utilCount > 1) {
            System.err.println("--show, --validate, and --clean are mutually exclusive");
            return 2;
        }
        if (show) return doShow();
        if (validate) return doValidate();
        if (cleanTarget != null) return doClean();
        return doRun();
    }

    // =================== Run mode ===================

    private int doRun() {
        if (fromPhase < 1 || fromPhase > 4) {
            System.err.println("--from-phase must be 1..4 (got " + fromPhase + ")");
            return 2;
        }
        if (toPhase < 1 || toPhase > 4) {
            System.err.println("--to-phase must be 1..4 (got " + toPhase + ")");
            return 2;
        }
        if (fromPhase > toPhase) {
            System.err.println("--from-phase (" + fromPhase + ") must be <= --to-phase (" + toPhase + ")");
            return 2;
        }
        if (auto && interactive) {
            System.err.println("--auto and --interactive are mutually exclusive (interactive requires the driver to send commands)");
            return 2;
        }
        if (feature == null || feature.isBlank()) {
            System.err.println("feature is required for run mode (e.g. `aethercode ssd add-foo \"add a foo\"`)");
            return 2;
        }
        String intent = String.join(" ", intentTokens).trim();
        if (intent.isEmpty()) {
            System.err.println("intent is required for run mode (one or more words describing the change)");
            return 2;
        }
        SsdConfig config = SsdConfig.fromProjectOrBundled(cwd);
        if (!interactive) {
            // Friendly preamble for terminal users. In interactive
            // mode we skip these so the JSON event stream stays
            // clean (every stdout byte must be a JSON object).
            System.out.println("[ssd] using " + config.source() + " config '"
                    + config.name() + "' (" + config.phases().size() + " phases)");
            System.out.println("[ssd] feature: " + feature);
            System.out.println("[ssd] intent:  " + intent);
            System.out.println("[ssd] cwd:     " + cwd);
            if (toPhase < 4) {
                System.out.println("[ssd] stopping after phase " + toPhase + " (--to-phase; dev phase skipped)");
            }
        }

        // Build the engine in-process. The CLI's Main holds a
        // package-private buildEngineForSession(String) helper
        // that does the full provider / skill / agent wiring;
        // we delegate to it. A null sessionId means "use
        // AppState's default", which is the right behaviour
        // for a one-shot CLI invocation.
        Main cli = new Main();
        cli.cwd = cwd;
        AetherCodeEngine engine = cli.buildEngineForSession(null);
        LlmFn llm = makeLlmFn(engine, config);
        ReplFn repl;
        final InteractiveRepl interactiveRepl;
        if (interactive) {
            interactiveRepl = InteractiveRepl.stdio(feature, config.orderedPhases());
            try {
                interactiveRepl.emitPhaseList();
            } catch (java.io.IOException ioe) {
                System.err.println("[ssd] failed to emit initial phase-list: " + ioe.getMessage());
                return 1;
            }
            repl = interactiveRepl;
        } else {
            interactiveRepl = null;
            repl = makeReplFn(auto);
        }
        Logger log = interactive ? (line -> interactiveRepl.log("info", line)) : line -> System.out.println(line);

        SsdRunner runner = new SsdRunner(config);
        try {
            List<PhaseResult> results = runner.runAll(cwd, feature, intent,
                    fromPhase, force, auto, llm, repl, log, toPhase);
            if (interactive) {
                interactiveRepl.emitComplete(results);
            } else {
                System.out.println();
                System.out.println("=".repeat(60));
                System.out.println("SSD complete. " + results.size() + " phase(s) produced artefacts:");
                for (PhaseResult r : results) {
                    System.out.println("  - " + r.phaseId() + ": " + r.artefactPath()
                            + (r.revisions() > 0 ? " (revised " + r.revisions() + "x)" : ""));
                }
            }
            return 0;
        } catch (AbortException ae) {
            if (!interactive) System.err.println("[ssd] aborted: " + ae.getMessage());
            return 2;
        } catch (Exception ex) {
            if (interactive) {
                // Best-effort error event so the UI can show a
                // banner before the subprocess exits with 1.
                try {
                    java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
                    ev.put("event", "error");
                    ev.put("message", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                    java.io.PrintStream err = System.err;
                    synchronized (err) {
                        err.println("{\"event\":\"error\",\"message\":\"" + (ex.getMessage() == null ? "" : ex.getMessage().replace("\"", "\\\"")) + "\"}");
                        err.flush();
                    }
                } catch (Exception ignore) {}
            } else {
                System.err.println("[ssd] failed: " + ex.getMessage());
                if (Boolean.getBoolean("aethercode.ssd.verbose")) {
                    ex.printStackTrace(System.err);
                }
            }
            return 1;
        }
    }

    // =================== Utility: --show ===================

    /** dump the resolved config so the operator can see
     *  exactly what SSD will run, including which phase
     *  templates, hard rules, and project-override source.
     *  Does not touch the LLM. */
    private int doShow() {
        SsdConfig config = SsdConfig.fromProjectOrBundled(cwd);
        System.out.println("source:        " + config.source());
        System.out.println("name:          " + config.name());
        System.out.println("artefactRoot:  " + config.artefactRoot());
        System.out.println("maxWaitMs:     " + config.maxWaitMs());
        System.out.println("idleEndMs:     " + config.idleEndMs());
        System.out.println("hardRules:");
        for (String line : config.hardRules().split("\n")) {
            System.out.println("  | " + line);
        }
        System.out.println("phases:");
        for (Phase p : config.orderedPhases()) {
            System.out.println("  - " + p.id() + " (order=" + p.order()
                    + ", file=" + p.file()
                    + ", maxTokens=" + p.maxTokens() + ")");
            String sys = p.systemPrompt();
            System.out.println("    systemPrompt: "
                    + (sys.length() > 100 ? sys.substring(0, 100) + "..." : sys.replace("\n", " | ")));
            System.out.println("    userPromptTemplate[0..100]: "
                    + p.userPromptTemplate().substring(0, Math.min(100, p.userPromptTemplate().length()))
                            .replace("\n", " | "));
        }
        return 0;
    }

    // =================== Utility: --validate ===================

    /** validate the resolved config end-to-end without
     *  running any LLM call. Runs the same checks
     *  {@code SsdConfig.parse} runs internally (4 required
     *  phase ids, non-blank prompts) plus a few practical
     *  sanity checks (no duplicate phase ids, orderedPhases
     *  is monotonic). */
    private int doValidate() {
        SsdConfig config = SsdConfig.fromProjectOrBundled(cwd);
        System.out.println("source: " + config.source() + ", name: " + config.name());
        List<String> errors = new java.util.ArrayList<>();
        // 1) duplicate phase ids
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (Phase p : config.phases()) {
            if (!seenIds.add(p.id())) {
                errors.add("duplicate phase id: " + p.id());
            }
        }
        // 2) order is unique and contiguous 1..N (not strictly
        //    required by the loader, but the operator should
        //    know if they accidentally duplicate an order).
        java.util.Set<Integer> orders = new java.util.HashSet<>();
        for (Phase p : config.phases()) {
            if (!orders.add(p.order())) {
                errors.add("duplicate order: " + p.order() + " (phase " + p.id() + ")");
            }
        }
        // 3) every required phase id present (loader already
        //    enforced this; we just double-check after the
        //    override fallback).
        for (String required : List.of("spec", "design", "tasks", "dev")) {
            if (config.phaseById(required).isEmpty()) {
                errors.add("missing required phase: " + required);
            }
        }
        // 4) every phase has a non-blank file, title, systemPrompt.
        for (Phase p : config.phases()) {
            if (p.file().isBlank()) errors.add("phase " + p.id() + " has blank file");
            if (p.title().isBlank()) errors.add("phase " + p.id() + " has blank title");
            if (p.systemPrompt().isBlank()) errors.add("phase " + p.id() + " has blank systemPrompt");
            if (p.userPromptTemplate().isBlank()) errors.add("phase " + p.id() + " has blank userPromptTemplate");
        }
        if (errors.isEmpty()) {
            System.out.println("OK: 4 phases, all required ids present, no duplicates, all prompts non-blank");
            return 0;
        }
        System.err.println("validation failed (" + errors.size() + " issue(s)):");
        for (String e : errors) {
            System.err.println("  - " + e);
        }
        return 1;
    }

    // =================== Utility: --clean ===================

    /** read <cleanTarget>, run {@link SsdRunner#cleanOutput},
     *  write the result to stdout. Lets operators test the
     *  cleanOutput pass against real LLM output without
     *  running a full SSD. Useful for debugging "why is my
     *  artefact missing the body" / "why is there a preamble"
     *  issues. */
    private int doClean() {
        if (!Files.isRegularFile(cleanTarget)) {
            System.err.println("--clean target is not a regular file: " + cleanTarget);
            return 2;
        }
        try {
            String text = Files.readString(cleanTarget, StandardCharsets.UTF_8);
            String cleaned = SsdRunner.cleanOutput(text);
            System.out.print(cleaned);
            if (!cleaned.endsWith("\n")) System.out.println();
            return 0;
        } catch (Exception ex) {
            System.err.println("--clean failed: " + ex.getMessage());
            return 1;
        }
    }

    // =================== LLM/REPL adapters (run mode only) ===================

    /** Build the {@link LlmFn} that talks to the engine. The
     *  hardRules block from {@link SsdConfig#hardRules()} is
     *  prepended to the rendered user prompt; the system prompt
     *  the engine itself sets is left untouched. We collect the
     *  streamed text into a StringBuilder, run
     *  {@link SsdRunner#cleanOutput(String)} on the result, and
     *  return. The runner has its own post-clean pass too, so
     *  this is belt-and-suspenders against chatty <think>
     *  preambles surviving the stream boundary. */
    private static LlmFn makeLlmFn(AetherCodeEngine engine, SsdConfig config) {
        String hardRules = config.hardRules();
        return (systemPrompt, userPrompt, maxTokens) -> {
            String combined;
            if (hardRules != null && !hardRules.isBlank()) {
                combined = "[SYSTEM ROLE]\n" + systemPrompt
                        + "\n\n" + hardRules
                        + "\n\n[USER REQUEST]\n" + userPrompt
                        + "\n\n[END]";
            } else {
                combined = "[SYSTEM ROLE]\n" + systemPrompt
                        + "\n\n[USER REQUEST]\n" + userPrompt
                        + "\n\n[END]";
            }
            StringBuilder sb = new StringBuilder();
            // The engine returns a Stream<StreamEvent>; we
            // consume it eagerly with forEach (the stream is
            // lazy, so we MUST drain it before returning the
            // cleaned text — the model is still streaming when
            // the first text_delta arrives). RunEnd signals
            // the end; we break out so we don't keep appending
            // into a closed stream.
            engine.query(combined).forEach(ev -> {
                if (ev instanceof StreamEvent.TextDelta td) {
                    sb.append(td.text());
                } else if (ev instanceof StreamEvent.RunEnd) {
                    // no-op; the stream terminates after this
                    // event anyway. We just don't keep going.
                }
                // We deliberately drop ToolUseStart / ToolResult
                // / SideNote. The model is hard-banned from
                // calling tools during SSD phases; if a tool
                // fires anyway (older model, prompt drift) the
                // output would be polluted, but the forEach
                // still returns text and cleanOutput() trims
                // the preamble.
            });
            return SsdRunner.cleanOutput(sb.toString());
        };
    }

    /** Build the {@link ReplFn}. The CLI reads stdin until the
     *  user sends {@code ok} / {@code next} / {@code continue}
     *  (accept), {@code q} / {@code quit} / {@code abort}
     *  (abort), or anything else (revision, queued until
     *  {@code ok}). Multiple revisions are concatenated with
     *  newlines so the runner can apply them in one re-run
     *  round. */
    private static ReplFn makeReplFn(boolean auto) {
        if (auto) {
            return (phaseTitle, artefact, content) -> {
                System.out.println("[ssd] auto-accept " + phaseTitle);
                return Optional.of("");
            };
        }
        return (phaseTitle, artefact, content) -> {
            System.out.println();
            System.out.println("=".repeat(60));
            System.out.println("Phase: " + phaseTitle);
            if (artefact != null) System.out.println("File:  " + artefact
                    + "  (" + (content == null ? 0 : content.length()) + " chars)");
            System.out.println("Reply with:");
            System.out.println("  ok / next / continue  — accept and move on");
            System.out.println("  q / quit / abort      — abort the whole SSD run");
            System.out.println("  <any text>           — revision; you can paste several lines,");
            System.out.println("                          they all queue until you type `ok`");
            System.out.println("=".repeat(60));
            StringBuilder buf = new StringBuilder();
            while (true) {
                System.out.print("> ");
                String line;
                try {
                    line = new java.io.BufferedReader(
                            new java.io.InputStreamReader(System.in))
                            .readLine();
                } catch (java.io.IOException ioe) {
                    // EOF on stdin (e.g. piped `echo ok | aethercode ssd ...`)
                    // = accept whatever revisions we already queued.
                    return buf.length() == 0 ? Optional.of("") : Optional.of(buf.toString());
                }
                if (line == null) {
                    // EOF from the same input source.
                    return buf.length() == 0 ? Optional.of("") : Optional.of(buf.toString());
                }
                String s = line.strip();
                if (s.equalsIgnoreCase("ok") || s.equalsIgnoreCase("next")
                        || s.equalsIgnoreCase("continue")
                        || s.equalsIgnoreCase("y") || s.equalsIgnoreCase("yes")) {
                    return buf.length() == 0 ? Optional.of("") : Optional.of(buf.toString());
                }
                if (s.equalsIgnoreCase("q") || s.equalsIgnoreCase("quit")
                        || s.equalsIgnoreCase("abort")
                        || s.equalsIgnoreCase("n") || s.equalsIgnoreCase("no")) {
                    return null;
                }
                if (s.isEmpty()) {
                    // bare newline = accept (matches Python's `input()` returning "")
                    return buf.length() == 0 ? Optional.of("") : Optional.of(buf.toString());
                }
                buf.append(s).append('\n');
                System.out.println("  (revision #" + (buf.toString().split("\n", -1).length)
                        + " queued; type 'ok' to apply)");
            }
        };
    }
}
