package org.aethercode.cli;

import org.aethercode.core.stream.StreamEvent;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.workflows.sdd.InteractiveRepl;
import org.aethercode.workflows.sdd.SddConfig;
import org.aethercode.workflows.sdd.SddConfig.PhaseId;
import org.aethercode.workflows.sdd.SddConfig.SlugPolicy;
import org.aethercode.workflows.sdd.SddRunner;
import org.aethercode.workflows.sdd.SddRunner.AbortException;
import org.aethercode.workflows.sdd.SddRunner.ContentProvider;
import org.aethercode.workflows.sdd.SddRunner.LlmFn;
import org.aethercode.workflows.sdd.SddRunner.Logger;
import org.aethercode.workflows.sdd.SddRunner.ReplFn;
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
 * R292 — the {@code aethercode sdd} CLI surface. Replaces the
 * R236 {@code aethercode ssd} command (Spec-Design-Tasks-Dev, 4
 * phases) with Spec Kit's 6-phase Spec-Driven Development model
 * (constitution/specify/plan/tasks/implement/converge) + 2
 * optional quality gates (clarify/analyze). Artifacts are written
 * under {@code <cwd>/.specify/specs/<NNN>-<slug>/} so they can be
 * consumed unchanged by any Spec Kit agent the user has installed
 * (Claude Code, Cursor, Copilot, etc.).
 *
 * <h2>Two modes</h2>
 * <ol>
 *   <li><b>Run mode (default)</b>: {@code aethercode sdd <feature> "..."}
 *       — runs the Spec Kit 6-phase pipeline. Supports
 *       {@code --from-phase}, {@code --to-phase}, {@code --force},
 *       {@code --auto}, {@code --interactive}, plus Spec-Kit-specific
 *       {@code --branch-numbering}, {@code --no-clarify},
 *       {@code --no-analyze}, {@code --no-converge}.</li>
 *   <li><b>Utility mode</b>: {@code aethercode sdd --show} dumps
 *       the resolved SDD config (loaded templates + flags); {@code
 *       --clean <file>} runs {@link SddRunner#cleanOutput(String)}
 *       on a file (useful for debugging real LLM output without
 *       running a full SDD).</li>
 * </ol>
 *
 * <h2>Why a CLI command (not a daemon RPC)</h2>
 * <p>The SDD runner is interactive (per-phase REPL) and only needs
 * a single LLM call per phase. Spawning a daemon just to drive it
 * would add a network round-trip + a notification-polling layer
 * with no benefit. Going in-process lets us use
 * {@link AetherCodeEngine#query(String)} directly and consume the
 * {@code Stream<StreamEvent>} with a simple forEach.
 *
 * <h2>Why a CLI command (not a workflow YAML)</h2>
 * <p>The workflow engine's executor is a single-pass spawner with
 * no human-in-the-loop gate step. Adding a {@code gate} step type
 * would be a much larger change than a focused orchestrator.
 * SDD's gate is "ask the user for revisions; loop on revision" —
 * the loop semantics are not a good fit for a step-based
 * workflow.
 */
@Command(
        name = "sdd",
        mixinStandardHelpOptions = true,
        description = {
                "Run the SDD (Spec-Driven Development) pipeline against an in-process",
                "AetherCodeEngine. Mirrors GitHub Spec Kit's 6-phase process",
                "(github/spec-kit): constitution → specify → [clarify] → plan →",
                "[analyze] → tasks → implement → converge. Each phase produces a",
                "markdown artefact under <cwd>/.specify/specs/<NNN>-<slug>/; you",
                "confirm or paste revisions between phases.",
                "",
                "Replaces the legacy `aethercode ssd` command (R236, 4-phase",
                "Spec-Design-Tasks-Dev). Spec Kit integration: see",
                "doc/round-notes/R292-SDD-SPEC-KIT-INTEGRATION.md.",
                "",
                "Two modes:",
                "  default:  sdd <feature> <intent...>   (run 6-phase pipeline)",
                "  utility:  sdd --show                   (dump resolved config)",
                "            sdd --clean <file>           (run SddRunner.cleanOutput on <file>)"
        }
)
public class SddCommand implements Callable<Integer> {

    @Parameters(arity = "0..1", description = "Feature name (kebab-case). Becomes the artefact directory slug (NNN-<feature>) under .specify/specs/. Required for run mode; ignored in utility mode.")
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
            description = "Run mode: skip the confirmation REPL (auto-accept every phase, skip optional quality gates).")
    boolean auto;

    @Option(names = {"--interactive", "-i"},
            description = "R292: run mode: emit newline-delimited JSON events to stdout and read JSON commands from stdin. Lets a UI (desktop SddPanel, TUI dashboard) drive the per-phase confirmation flow instead of typing into a terminal. Mutually exclusive with --auto.")
    boolean interactive;

    @Option(names = {"--from-phase"},
            description = "Run mode: start at phase N (0..7). Earlier artefacts are reused. Default: 0 (run constitution on the first SDD run, then skip on later runs unless --force).")
    int fromPhase = 0;

    @Option(names = {"--to-phase"},
            description = "Run mode: stop after phase N (0..7). Default: 7 (full run including converge). Use --to-phase 6 to skip converge for a quick demo.")
    int toPhase = 7;

    @Option(names = {"--branch-numbering"},
            description = "Run mode: slug numbering strategy. sequential (default — 001-feature-name) or timestamp (YYYYMMDD-HHMMSS-feature-name).")
    String branchNumbering;

    @Option(names = {"--no-clarify"},
            description = "Run mode: skip the optional /speckit.clarify quality gate between specify and plan.")
    boolean noClarify;

    @Option(names = {"--no-analyze"},
            description = "Run mode: skip the optional /speckit.analyze quality gate between plan and tasks.")
    boolean noAnalyze;

    @Option(names = {"--no-converge"},
            description = "Run mode: skip the optional /speckit.converge loop after implement.")
    boolean noConverge;

    @Option(names = {"--show"},
            description = "Utility mode: print the resolved SDD config (slug policy, active phases, hard-rules source, bundled constitution source) and exit. Does NOT touch the LLM.")
    boolean show;

    @Option(names = {"--clean"}, paramLabel = "<file>",
            description = "Utility mode: read <file>, run SddRunner.cleanOutput, write to stdout. Useful for testing cleanOutput against real LLM output without running a full SDD.")
    Path cleanTarget;

    @Override
    public Integer call() {
        // ----- Mode dispatch -----
        int utilCount = (show ? 1 : 0) + (cleanTarget != null ? 1 : 0);
        if (utilCount > 1) {
            System.err.println("--show and --clean are mutually exclusive");
            return 2;
        }
        if (show) return doShow();
        if (cleanTarget != null) return doClean();
        return doRun();
    }

    // =================== Run mode ===================

    private int doRun() {
        if (fromPhase < 0 || fromPhase > 7) {
            System.err.println("--from-phase must be 0..7 (got " + fromPhase + ")");
            return 2;
        }
        if (toPhase < 0 || toPhase > 7) {
            System.err.println("--to-phase must be 0..7 (got " + toPhase + ")");
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
            System.err.println("feature is required for run mode (e.g. `aethercode sdd add-foo \"add a foo\"`)");
            return 2;
        }
        String intent = String.join(" ", intentTokens).trim();
        if (intent.isEmpty()) {
            System.err.println("intent is required for run mode (one or more words describing the change)");
            return 2;
        }

        SddConfig config;
        try {
            config = SddConfig.fromProjectOrBundled(cwd);
            // Apply CLI flag overrides on top of project / bundled defaults.
            if (branchNumbering != null) {
                config = config.withSlugPolicy(SlugPolicy.parse(branchNumbering));
            }
            if (noClarify) config = config.withEnableClarify(false);
            if (noAnalyze) config = config.withEnableAnalyze(false);
            if (noConverge) config = config.withEnableConverge(false);
        } catch (IllegalArgumentException iae) {
            System.err.println("[sdd] config error: " + iae.getMessage());
            return 2;
        }

        if (!interactive) {
            System.out.println("[sdd] using " + config.constitutionSource() + " constitution, "
                    + config.hardRulesSource() + " hard-rules, slugPolicy=" + config.slugPolicy());
            System.out.println("[sdd] active phases: " + config.activePhases().size()
                    + " (" + config.activePhases().stream().map(PhaseId::specKitId).toList() + ")");
            System.out.println("[sdd] feature: " + feature);
            System.out.println("[sdd] intent:  " + intent);
            System.out.println("[sdd] cwd:     " + cwd);
            if (toPhase < 7) {
                System.out.println("[sdd] stopping after phase " + toPhase + " (--to-phase; converge skipped)");
            }
        }

        // Build the engine in-process. The CLI's Main holds a
        // package-private buildEngineForSession(String) helper
        // that does the full provider / skill / agent wiring;
        // we delegate to it. A null sessionId means "use
        // AppState's default", which is the right behaviour
        // for a one-shot CLI invocation.
        // R309: the runner no longer hardwires an LLM call;
        // we hand it a {@link ContentProvider} that can be either
        // the wire-protocol InteractiveRepl (interactive mode:
        // driver / agent supplies content over stdin) or a
        // legacy {@link LlmFn} backed by the in-process
        // AetherCodeEngine (auto / terminal mode: daemon does
        // its own LLM call). The daemon's SddRunner only knows
        // about the {@link ContentProvider} seam — the choice
        // happens here.
        ContentProvider content;
        ReplFn repl;
        final InteractiveRepl interactiveRepl;
        final AetherCodeEngine engine;
        if (interactive) {
            // Interactive mode: the driver forwards
            // phase-need-content events to its attached LLM
            // (Mavis agent / manual paste) and replies with
            // phase-content commands. No engine needed in the
            // daemon process.
            SddRunner r = new SddRunner(config);
            String slug = r.nextSlug(cwd, feature);
            interactiveRepl = InteractiveRepl.stdio(slug, config.activePhases());
            try {
                interactiveRepl.emitPhaseList();
            } catch (java.io.IOException ioe) {
                System.err.println("[sdd] failed to emit initial phase-list: " + ioe.getMessage());
                return 1;
            }
            repl = interactiveRepl;
            content = interactiveRepl;
            engine = null;
        } else if (auto) {
            // R298: --auto also wires InteractiveRepl so the daemon
            // emits phase-list / phase-start / phase-draft /
            // phase-accepted / complete events on stdout. The
            // pre-R298 --auto path skipped InteractiveRepl entirely,
            // which meant the renderer's chip strip never flipped
            // and the user saw the same "flash past" symptom as the
            // MockSsdDriver canned fallback. InteractiveRepl.autoAccept
            // supplies a synthetic InputStream that hands the REPL a
            // fresh {"action":"accept"}\n on every readReply call,
            // so the run auto-progresses internally while still
            // surfacing every chip transition.
            //
            // R309: --auto still calls the in-process LLM
            // (AetherCodeEngine.query) because there's no driver
            // attached to feed content over stdin. We wrap the
            // legacy LlmFn as a ContentProvider so SddRunner's
            // new seam doesn't care which mode we're in.
            Main cli = new Main();
            cli.cwd = cwd;
            engine = cli.buildEngineForSession(null);
            final LlmFn legacyLlm = makeLlmFn(engine, config);
            SddRunner r = new SddRunner(config);
            String slug = r.nextSlug(cwd, feature);
            interactiveRepl = InteractiveRepl.autoAccept(slug, config.activePhases());
            try {
                interactiveRepl.emitPhaseList();
            } catch (java.io.IOException ioe) {
                System.err.println("[sdd] failed to emit initial phase-list: " + ioe.getMessage());
                return 1;
            }
            repl = interactiveRepl;
            content = (sys, usr, max) -> legacyLlm.generate(sys, usr, max);
        } else {
            // Legacy terminal REPL — also keeps the
            // AetherCodeEngine-backed LlmFn path so a manual
            // `aethercode sdd` invocation in a terminal still
            // produces a useful artefact (R292 behaviour).
            Main cli = new Main();
            cli.cwd = cwd;
            engine = cli.buildEngineForSession(null);
            final LlmFn legacyLlm = makeLlmFn(engine, config);
            interactiveRepl = null;
            repl = makeReplFn(auto);
            content = (sys, usr, max) -> legacyLlm.generate(sys, usr, max);
        }
        Logger log = interactive ? (line -> interactiveRepl.log("info", line)) : line -> System.out.println(line);

        SddRunner runner = new SddRunner(config);
        try {
            String slug = runner.nextSlug(cwd, feature);
            List<SddRunner.PhaseResult> results = runner.runAll(cwd, slug, intent,
                    fromPhase, force, auto, content, repl, log, toPhase);
            if (interactive) {
                interactiveRepl.emitComplete(results);
            } else {
                System.out.println();
                System.out.println("=".repeat(60));
                System.out.println("SDD complete. " + results.size() + " phase(s) produced artefacts:");
                for (SddRunner.PhaseResult r : results) {
                    System.out.println("  - " + r.phaseId() + ": " + r.artefactPath()
                            + (r.revisions() > 0 ? " (revised " + r.revisions() + "x)" : ""));
                }
            }
            return 0;
        } catch (AbortException ae) {
            if (!interactive) System.err.println("[sdd] aborted: " + ae.getMessage());
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
                        err.println("{\"event\":\"error\",\"message\":\""
                                + (ex.getMessage() == null ? "" : ex.getMessage().replace("\"", "\\\"")) + "\"}");
                        err.flush();
                    }
                } catch (Exception ignore) {}
            } else {
                System.err.println("[sdd] failed: " + ex.getMessage());
                if (Boolean.getBoolean("aethercode.sdd.verbose")) {
                    ex.printStackTrace(System.err);
                }
            }
            return 1;
        } finally {
            // R293: tear down the bounded-read executor so the
            // JVM is free to exit. Without this the desktop
            // driver sees the `sdd` subprocess hang on exit
            // (and its `kill()` has to wait the full 5-min
            // timeout). Exception path is the same as the
            // success path — close unconditionally.
            if (interactiveRepl != null) {
                try { interactiveRepl.close(); } catch (Exception ignore) {}
            }
        }
    }

    // =================== Utility: --show ===================

    /** Dump the resolved SDD config so the operator can see
     *  exactly what SDD will run: bundled vs project sources,
     *  active phase list, slug policy, hard-rules source. Does
     *  not touch the LLM. */
    private int doShow() {
        SddConfig config;
        try {
            config = SddConfig.fromProjectOrBundled(cwd);
        } catch (IllegalArgumentException iae) {
            System.err.println("[sdd] config error: " + iae.getMessage());
            return 2;
        }
        System.out.println("name:                   " + config.name());
        System.out.println("description:            " + (config.description().isBlank() ? "(none)" : config.description()));
        System.out.println("constitution source:    " + config.constitutionSource());
        System.out.println("hard-rules source:      " + config.hardRulesSource());
        System.out.println("slug policy:            " + config.slugPolicy());
        System.out.println("artefact root:          " + config.artefactRoot());
        System.out.println("enable clarify:         " + config.enableClarify());
        System.out.println("enable analyze:         " + config.enableAnalyze());
        System.out.println("enable converge:        " + config.enableConverge());
        System.out.println("active phases (" + config.activePhases().size() + "):");
        for (PhaseId p : config.activePhases()) {
            System.out.println("  - " + p.specKitId() + " (order=" + p.order()
                    + ", optional=" + p.isOptional() + ", template=" + config.phaseSource(p) + ")");
        }
        System.out.println("constitution body (first 200 chars):");
        String body = config.constitutionBody();
        String head = body.length() > 200 ? body.substring(0, 200) + "..." : body;
        for (String line : head.split("\n")) {
            System.out.println("  | " + line);
        }
        return 0;
    }

    // =================== Utility: --clean ===================

    /** Read <cleanTarget>, run {@link SddRunner#cleanOutput},
     *  write the result to stdout. Lets operators test the
     *  cleanOutput pass against real LLM output without
     *  running a full SDD. Useful for debugging "why is my
     *  artefact missing the body" / "why is there a preamble"
     *  issues. */
    private int doClean() {
        if (!Files.isRegularFile(cleanTarget)) {
            System.err.println("--clean target is not a regular file: " + cleanTarget);
            return 2;
        }
        try {
            String text = Files.readString(cleanTarget, StandardCharsets.UTF_8);
            String cleaned = SddRunner.cleanOutput(text);
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
     *  hardRules block from {@link SddConfig#hardRules()} is
     *  prepended to the rendered user prompt; the system prompt
     *  the engine itself sets is left untouched. We collect the
     *  streamed text into a StringBuilder, run
     *  {@link SddRunner#cleanOutput(String)} on the result, and
     *  return. The runner has its own post-clean pass too, so
     *  this is belt-and-suspenders against chatty <think>
     *  preambles surviving the stream boundary. */
    private static LlmFn makeLlmFn(AetherCodeEngine engine, SddConfig config) {
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
            engine.query(combined).forEach(ev -> {
                if (ev instanceof StreamEvent.TextDelta td) {
                    sb.append(td.text());
                } else if (ev instanceof StreamEvent.RunEnd) {
                    // no-op; the stream terminates after this
                    // event anyway. We just don't keep going.
                }
                // Drop ToolUseStart / ToolResult / SideNote.
            });
            return SddRunner.cleanOutput(sb.toString());
        };
    }

    /** Build the {@link ReplFn} for the legacy terminal driver.
     *  The CLI reads stdin until the user sends {@code ok} /
     *  {@code next} / {@code continue} (accept), {@code q} /
     *  {@code quit} / {@code abort} (abort), or anything else
     *  (revision, queued until {@code ok}). Multiple revisions
     *  are concatenated with newlines so the runner can apply
     *  them in one re-run round. */
    private static ReplFn makeReplFn(boolean auto) {
        if (auto) {
            return (phaseTitle, artefact, content) -> {
                System.out.println("[sdd] auto-accept " + phaseTitle.specKitId());
                return Optional.of("");
            };
        }
        return (phase, artefact, content) -> {
            System.out.println();
            System.out.println("=".repeat(60));
            System.out.println("Phase: " + phase.title() + " (" + phase.specKitId() + ")"
                    + (phase.isOptional() ? " — optional quality gate" : ""));
            if (artefact != null) System.out.println("File:  " + artefact
                    + "  (" + (content == null ? 0 : content.length()) + " chars)");
            System.out.println("Reply with:");
            System.out.println("  ok / next / continue  — accept and move on");
            System.out.println("  q / quit / abort      — abort the whole SDD run");
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
                    return buf.length() == 0 ? Optional.of("") : Optional.of(buf.toString());
                }
                if (line == null) {
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
                    return buf.length() == 0 ? Optional.of("") : Optional.of(buf.toString());
                }
                buf.append(s).append('\n');
                System.out.println("  (revision #" + (buf.toString().split("\n", -1).length)
                        + " queued; type 'ok' to apply)");
            }
        };
    }
}