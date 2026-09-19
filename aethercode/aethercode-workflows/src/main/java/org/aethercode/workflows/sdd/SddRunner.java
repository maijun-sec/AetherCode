package org.aethercode.workflows.sdd;

import org.aethercode.workflows.sdd.SddConfig.PhaseId;
import org.aethercode.workflows.sdd.SddConfig.SlugPolicy;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R292 — SDD (Spec-Driven Development) orchestrator. Drives Spec
 * Kit's 6-phase pipeline end-to-end with per-phase human-in-the-loop
 * confirmation. Replaces the R236 {@code SsdRunner} (spec/design/
 * tasks/dev) with Spec Kit's richer model:
 *
 * <ol>
 *   <li>{@code constitution} — establish project governance
 *       (one-time per project, lives at
 *       {@code .specify/memory/constitution.md}).</li>
 *   <li>{@code specify} — write the requirements document
 *       ({@code spec.md}) using user stories, FRs, success
 *       criteria.</li>
 *   <li>{@code clarify} (optional) — surface underspecified areas
 *       in {@code spec.md} as a Q&amp;A; the runner asks the
 *       driver via {@code clarify-question} NDJSON events and
 *       applies the answers via a follow-up model call.</li>
 *   <li>{@code plan} — write the technical plan
 *       ({@code plan.md}) with stack, structure, constitution
 *       check.</li>
 *   <li>{@code analyze} (optional) — cross-artifact consistency
 *       check (spec vs. plan vs. tasks); the runner emits the
 *       findings as a NDJSON {@code analysis} event and proceeds.</li>
 *   <li>{@code tasks} — decompose into tasks.md (T-NNN ids,
 *       {@code [P]} parallel markers, story labels).</li>
 *   <li>{@code implement} — walk every task in tasks.md and ask
 *       the model to produce code; the per-task output streams to
 *       {@code logs/implement.log}.</li>
 *   <li>{@code converge} — post-implementation review loop;
 *       runner asks the model to assess convergence and emits a
 *       NDJSON {@code converge-check} event; if not converged the
 *       user can iterate (NDJSON {@code converge-iterate} command)
 *       or {@code skip} / {@code quit}.</li>
 * </ol>
 *
 * <h2>Per-phase contract</h2>
 * For each non-{@code implement} phase:
 * <ol>
 *   <li>check whether the artefact already exists; if so and
 *       {@code force} is false, ask the user to keep, revise,
 *       or abort;</li>
 *   <li>build the system + user prompt by concatenating the
 *       constitution, the per-phase template, and the hard-rules
 *       block (R292 hard-rules block is identical to R236's —
 *       no <think>, no preamble, no tool calls);</li>
 *   <li>call {@link LlmFn#generate(String, String, int)} and run
 *       {@link #cleanOutput(String)} on the response;</li>
 *   <li>write the artefact to disk under
 *       {@code <cwd>/.specify/specs/<NNN>-<slug>/};</li>
 *   <li>ask the user to accept or paste a revision. If a
 *       revision is given, loop back to (2) with the just-written
 *       artefact inlined as {@code priorContent}.</li>
 * </ol>
 *
 * <h2>Implement phase</h2>
 * <p>The implement phase parses the task table from {@code tasks.md}
 * using {@link #parseTasks(String)}, walks every row, and asks the
 * model to implement each one. Each row's model output is appended
 * to {@code logs/implement.log}. The runner does not actually
 * execute the model's suggestions — it surfaces them as a log the
 * user (or the daemon's tool layer) can act on.
 *
 * <h2>Converge phase</h2>
 * <p>The runner asks the model "is the implementation complete
 * and consistent?" using a fixed prompt that includes the latest
 * {@code implement.log} summary and the original
 * {@code spec.md}. The model emits a JSON report with
 * {@code converged: boolean} and {@code issues: [...]}. If
 * {@code converged=true} we emit {@code complete} and exit. If
 * {@code converged=false} we emit {@code converge-check} and
 * wait for a {@code converge-iterate} (with feedback text) or
 * {@code skip} command.
 */
public final class SddRunner {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(SddRunner.class);

    // ---- task table parser (mirrors Spec Kit's [P] [US] markers) ----

    /** Tolerant row matcher: accepts both {@code | T001 ... |}
     *  (Spec Kit default table format) and {@code | T-1.2.3 ... |}
     *  (R236 legacy). The {@code [P]} / {@code [USn]} markers
     *  are optional; the columns may be back-ticked paths. */
    private static final Pattern TASK_ROW = Pattern.compile(
            "^\\s*\\|\\s*(T-?\\d+(?:\\.\\d+)*)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*(\\d+)\\s*\\|\\s*([^|]+?)\\s*\\|",
            Pattern.MULTILINE);

    /** Checklist-style fallback used when tasks.md is a
     *  bullet list instead of a table. Matches
     *  {@code - [ ] T001 [P] [US1] description} (the [P] /
     *  [USn] markers are optional). Description is the rest of
     *  the line; est / acceptance / files are not captured —
     *  the implement phase just emits the description back as
     *  the prompt context. */
    private static final Pattern TASK_CHECKLIST = Pattern.compile(
            "^\\s*-\\s*\\[\\s*[xX ]\\s*\\]\\s*(T-?\\d+(?:\\.\\d+)*)\\b(.*)$",
            Pattern.MULTILINE);

    /** JSON-ish "converged": true/false line for the converge
     *  check. We accept a small set of shapes the model is known
     *  to emit; everything else falls back to "not converged". */
    private static final Pattern CONVERGED_TRUE = Pattern.compile(
            "(?i)\\bconverged\\s*[:=]\\s*(?:true|yes|✓|converged)\\b");
    private static final Pattern CONVERGED_FALSE = Pattern.compile(
            "(?i)\\bconverged\\s*[:=]\\s*(?:false|no|✗|not.?converged)\\b");

    private final SddConfig config;

    public SddRunner(SddConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Run the pipeline. Mirrors {@code SsdRunner.runAll} so the
     *  {@code SddCommand} / {@code InteractiveRepl} wiring stays
     *  minimal. */
    public List<PhaseResult> runAll(Path cwd,
                                    String slug,
                                    String intent,
                                    int fromOrder,
                                    boolean force,
                                    boolean auto,
                                    LlmFn llm,
                                    ReplFn repl,
                                    Logger log,
                                    int toOrder) throws Exception {
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(llm, "llm");
        Objects.requireNonNull(repl, "repl");
        Objects.requireNonNull(log, "log");

        if (fromOrder < 0 || fromOrder > 7) {
            throw new IllegalArgumentException("fromOrder must be 0..7, got " + fromOrder);
        }
        if (toOrder < 0 || toOrder > 7) {
            throw new IllegalArgumentException("toOrder must be 0..7, got " + toOrder);
        }
        if (fromOrder > toOrder) {
            throw new IllegalArgumentException("fromOrder (" + fromOrder + ") must be <= toOrder (" + toOrder + ")");
        }

        Path featureDir = artefactDir(cwd, slug);
        // Spec Kit spec: constitution lives at
        // `.specify/memory/constitution.md`, not under the
        // feature dir. Create both directories up front so
        // every phase's writeFile() finds a parent.
        Files.createDirectories(cwd.resolve(config.artefactRoot()).resolve("memory"));
        Files.createDirectories(featureDir);
        Files.createDirectories(featureDir.resolve("logs"));
        log.log("[sdd] slug: " + slug);
        log.log("[sdd] intent: " + intent);
        log.log("[sdd] feature dir: " + featureDir);

        List<PhaseResult> results = new ArrayList<>();
        List<PhaseId> active = config.activePhases();

        Map<String, Path> writtenPaths = new LinkedHashMap<>();
        Map<String, Object> bodies = new LinkedHashMap<>();
        bodies.put("slug", slug);
        bodies.put("intent", intent);
        bodies.put("cwd", cwd.toString());
        bodies.put("date", LocalDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));

        for (PhaseId phase : active) {
            if (phase.order() < fromOrder) {
                log.log("[sdd] skipping phase " + phase.order() + " (" + phase.specKitId() + ") — --from-phase=" + fromOrder);
                continue;
            }
            if (phase.order() > toOrder) {
                log.log("[sdd] stopping at phase " + toOrder + " (" + phase.specKitId() + " skipped) — --to-phase=" + toOrder);
                break;
            }
            // Tell the UI which phase is starting so it can flip the
            // corresponding chip to "running".
            if (repl instanceof InteractiveRepl ir) {
                try { ir.emitPhaseStart(phase); } catch (Exception ignore) {}
            }

            switch (phase) {
                case CONSTITUTION -> {
                    Path p = constitutionPath(cwd);
                    int revs = runDraftPhase(phase, p, force, auto, llm, repl, log, bodies, "");
                    writtenPaths.put("constitution.md", p);
                    bodies.put("constitutionContent", bodies.getOrDefault(phase.specKitId() + ".content", ""));
                    results.add(new PhaseResult(phase.specKitId(), p, revs));
                }
                case SPECIFY -> {
                    Path p = featureDir.resolve("spec.md");
                    String prior = readIfPresent(constitutionPath(cwd));
                    int revs = runDraftPhase(phase, p, force, auto, llm, repl, log, bodies, prior);
                    bodies.put("specContent", bodies.getOrDefault(phase.specKitId() + ".content", ""));
                    writtenPaths.put("spec.md", p);
                    results.add(new PhaseResult(phase.specKitId(), p, revs));
                }
                case CLARIFY -> {
                    int revs = runClarify(phase, featureDir, auto, llm, repl, log, bodies);
                    results.add(new PhaseResult(phase.specKitId(), featureDir.resolve("clarify.json"), revs));
                }
                case PLAN -> {
                    Path p = featureDir.resolve("plan.md");
                    String prior = readIfPresent(featureDir.resolve("spec.md"));
                    int revs = runDraftPhase(phase, p, force, auto, llm, repl, log, bodies, prior);
                    bodies.put("planContent", bodies.getOrDefault(phase.specKitId() + ".content", ""));
                    writtenPaths.put("plan.md", p);
                    results.add(new PhaseResult(phase.specKitId(), p, revs));
                }
                case ANALYZE -> {
                    int revs = runAnalyze(phase, featureDir, auto, llm, repl, log);
                    results.add(new PhaseResult(phase.specKitId(), featureDir.resolve("analyze.json"), revs));
                }
                case TASKS -> {
                    Path p = featureDir.resolve("tasks.md");
                    String prior = readIfPresent(featureDir.resolve("plan.md"));
                    int revs = runDraftPhase(phase, p, force, auto, llm, repl, log, bodies, prior);
                    bodies.put("tasksContent", bodies.getOrDefault(phase.specKitId() + ".content", ""));
                    writtenPaths.put("tasks.md", p);
                    results.add(new PhaseResult(phase.specKitId(), p, revs));
                }
                case IMPLEMENT -> {
                    Path tasksPath = featureDir.resolve("tasks.md");
                    int revs = runImplement(phase, tasksPath, auto, llm, repl, log);
                    Path implLog = featureDir.resolve("logs").resolve("implement.log");
                    results.add(new PhaseResult(phase.specKitId(), implLog, revs));
                }
                case CONVERGE -> {
                    int revs = runConverge(phase, featureDir, auto, llm, repl, log);
                    Path conv = featureDir.resolve("convergence.json");
                    results.add(new PhaseResult(phase.specKitId(), conv, revs));
                }
            }
        }
        return results;
    }

    // ---- per-phase runs -----------------------------------------------

    /** The shared "draft + ask" loop used by every per-file phase
     *  (constitution, specify, plan, tasks). Caller passes the
     *  artefact path; we drive the LLM and let the user revise
     *  until they accept. The {@code priorContent} is what the
     *  per-phase switch decided was the prior artefact (constitution
     *  for specify, spec.md for plan, plan.md for tasks). */
    private int runDraftPhase(PhaseId phase, Path outFile,
                              boolean force, boolean auto,
                              LlmFn llm, ReplFn repl, Logger log,
                              Map<String, Object> bodies, String priorContent) throws Exception {
        int revisionsThisPhase = 0;
        String revision = null;
        String lastContent = "";

        if (Files.isRegularFile(outFile) && !force) {
            String existing = Files.readString(outFile, StandardCharsets.UTF_8);
            log.log("[sdd] " + outFile.getFileName() + " already exists (" + existing.length() + " chars); asking");
            Optional<String> reply = repl.confirm(phase, outFile, existing);
            if (reply == null) {
                throw new AbortException("user aborted at existing " + outFile.getFileName());
            }
            if (reply.isPresent() && !reply.get().isBlank()) {
                revision = reply.get();
            } else {
                log.log("[sdd] reusing existing " + outFile.getFileName());
                bodies.put(phase.specKitId() + ".content", existing);
                return 0;
            }
        }

        while (true) {
            String systemPrompt = buildSystemPrompt(phase);
            String userTemplate = config.phaseTemplate(phase);
            String userPrompt;
            if (revision != null) {
                userPrompt = revisionPrompt(userTemplate, lastContent, revision);
                log.log("[sdd] phase " + phase.specKitId() + " (revision #" + (revisionsThisPhase + 1) + ")");
            } else {
                Map<String, Object> bag = new LinkedHashMap<>(bodies);
                bag.put("priorContent", lastContent.isEmpty() ? priorContent : lastContent);
                userPrompt = SddConfig.substitute(userTemplate, bag);
                log.log("[sdd] phase " + phase.specKitId() + " (initial)");
            }
            String text = cleanOutput(llm.generate(systemPrompt, userPrompt, 4096));
            Files.writeString(outFile, text, StandardCharsets.UTF_8);
            log.log("[sdd] wrote " + outFile + " (" + text.length() + " chars)");
            lastContent = text;

            if (auto) {
                log.log("[sdd] auto-accept on " + outFile.getFileName());
                bodies.put(phase.specKitId() + ".content", lastContent);
                return revisionsThisPhase;
            }
            Optional<String> reply = repl.confirm(phase, outFile, lastContent);
            if (reply == null) {
                throw new AbortException("user aborted at " + outFile.getFileName());
            }
            if (reply.isEmpty() || reply.get().isBlank()) {
                log.log("[sdd] accepted " + outFile.getFileName());
                bodies.put(phase.specKitId() + ".content", lastContent);
                return revisionsThisPhase;
            }
            revision = reply.get();
            revisionsThisPhase++;
            if (repl instanceof InteractiveRepl ir) {
                ir.recordRevision(phase);
            }
        }
    }

    /** Clarify: ask the model for 1-3 questions about underspecified
     *  areas in spec.md, then forward each as a NDJSON
     *  {@code clarify-question} event. The driver answers via
     *  {@code clarify-answer}; we accumulate the answers and ask
     *  the model to apply them to spec.md in one follow-up call.
     *
     * <p>Always writes a {@code clarify.json} so the UI + tests
     * have a stable record of what happened (questions surfaced,
     * answers received, applied-or-skipped). Auto mode writes an
     * empty manifest; non-interactive mode writes the same. */
    private int runClarify(PhaseId phase, Path featureDir, boolean auto,
                           LlmFn llm, ReplFn repl, Logger log,
                           Map<String, Object> bodies) throws Exception {
        Path specPath = featureDir.resolve("spec.md");
        Path clarifyOut = featureDir.resolve("clarify.json");
        // R292: in auto mode we still record that clarify was
        // skipped (questions=0, answered=0) so the UI + tests can
        // tell the difference between "the model saw nothing to
        // ask" and "the runner never reached this phase".
        if (auto) {
            log.log("[sdd] auto mode — skipping clarify questions");
            try {
                Files.writeString(clarifyOut,
                        "{\n  \"questions\": 0,\n  \"answered\": 0,\n  \"auto\": true\n}\n",
                        StandardCharsets.UTF_8);
            } catch (java.io.IOException ioe) {
                log.log("[sdd] could not write " + clarifyOut + ": " + ioe.getMessage());
            }
            return 0;
        }
        if (!(repl instanceof InteractiveRepl ir)) {
            log.log("[sdd] non-interactive REPL — skipping clarify questions");
            try {
                Files.writeString(clarifyOut,
                        "{\n  \"questions\": 0,\n  \"answered\": 0,\n  \"non_interactive\": true\n}\n",
                        StandardCharsets.UTF_8);
            } catch (java.io.IOException ioe) {
                log.log("[sdd] could not write " + clarifyOut + ": " + ioe.getMessage());
            }
            return 0;
        }
        if (!Files.isRegularFile(specPath)) {
            log.log("[sdd] no spec.md found; skipping clarify");
            try {
                Files.writeString(clarifyOut,
                        "{\n  \"questions\": 0,\n  \"answered\": 0,\n  \"no_spec\": true\n}\n",
                        StandardCharsets.UTF_8);
            } catch (java.io.IOException ioe) {
                log.log("[sdd] could not write " + clarifyOut + ": " + ioe.getMessage());
            }
            return 0;
        }
        String specText = Files.readString(specPath, StandardCharsets.UTF_8);
        // Ask the model for 1-3 questions. We don't try to parse
        // the output strictly; the driver just renders whatever
        // the model emits (numbered list works fine).
        String questionPrompt = "Read the spec below and surface 1-3 short questions about areas that are underspecified. "
                + "One question per line, prefixed with 'Q:'. If nothing is unclear, output 'Q: NONE'.\n\n"
                + "```\n" + specText + "\n```";
        String qResponse = cleanOutput(llm.generate(
                "You surface underspecified areas in software specs.",
                questionPrompt, 1024));
        List<String> questions = new ArrayList<>();
        for (String line : qResponse.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("Q:") && !t.equalsIgnoreCase("Q: NONE")) {
                questions.add(t.substring(2).trim());
                if (questions.size() >= 3) break;
            }
        }
        if (questions.isEmpty()) {
            log.log("[sdd] no clarify questions surfaced");
            return 0;
        }
        Map<String, String> answers = new LinkedHashMap<>();
        List<String> askedQs = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            String id = "q" + (i + 1);
            String answer = ir.askClarify(id, "Clarify", questions.get(i));
            if (answer == null) {
                log.log("[sdd] user skipped clarify question " + id);
                break;
            }
            answers.put(id, answer);
            askedQs.add(questions.get(i));
        }
        // Apply the answers to spec.md in one follow-up call.
        if (!answers.isEmpty()) {
            StringBuilder applyPrompt = new StringBuilder("Update spec.md below to incorporate the following answers. "
                    + "Output the entire revised spec.md (no preamble, no closing line).\n\n");
            int idx = 0;
            for (Map.Entry<String, String> e : answers.entrySet()) {
                applyPrompt.append(e.getKey()).append(": ").append(askedQs.get(idx))
                        .append("\n  → answer: ").append(e.getValue()).append("\n\n");
                idx++;
            }
            applyPrompt.append("Current spec.md:\n```\n").append(specText).append("\n```");
            String updated = cleanOutput(llm.generate(
                    "You revise software spec documents based on user clarifications.",
                    applyPrompt.toString(), 4096));
            Files.writeString(specPath, updated, StandardCharsets.UTF_8);
            bodies.put(phase.specKitId() + ".content", updated);
        }
        Files.writeString(clarifyOut, "{\n  \"questions\": "
                + questions.size() + ",\n  \"answered\": " + answers.size() + "\n}\n", StandardCharsets.UTF_8);
        return answers.size();
    }

    /** Analyze: ask the model to cross-check spec.md / plan.md /
     *  tasks.md for consistency. Emit the findings as a single
     *  NDJSON {@code analysis} event and proceed — we don't gate
     *  on the findings (Spec Kit's {@code /speckit.analyze} doesn't
     *  either; the user reads the report and decides). */
    private int runAnalyze(PhaseId phase, Path featureDir, boolean auto,
                           LlmFn llm, ReplFn repl, Logger log) throws Exception {
        if (!(repl instanceof InteractiveRepl ir)) {
            log.log("[sdd] non-interactive REPL — skipping analyze");
            return 0;
        }
        if (auto) {
            log.log("[sdd] auto mode — skipping analyze");
            return 0;
        }
        String spec = readIfPresent(featureDir.resolve("spec.md"));
        String plan = readIfPresent(featureDir.resolve("plan.md"));
        String tasks = readIfPresent(featureDir.resolve("tasks.md"));
        if (spec.isBlank() || plan.isBlank() || tasks.isBlank()) {
            log.log("[sdd] missing one of spec/plan/tasks; skipping analyze");
            return 0;
        }
        String prompt = "Cross-check the three artefacts below for consistency. "
                + "List any requirement in spec.md that is not covered by tasks.md, "
                + "any task in tasks.md that is not justified by plan.md, "
                + "or any conflict between them. "
                + "Output a short bullet list of issues, then a line 'CONVERGED: yes' or 'CONVERGED: no'.\n\n"
                + "# spec.md\n" + spec + "\n\n# plan.md\n" + plan + "\n\n# tasks.md\n" + tasks;
        String response = cleanOutput(llm.generate(
                "You are a software consistency analyst.",
                prompt, 2048));
        Path out = featureDir.resolve("analyze.json");
        Files.writeString(out, response, StandardCharsets.UTF_8);
        try { ir.emitAnalysis(phase, response); } catch (Exception ignore) {}
        return 1;
    }

    /** Implement: walk the tasks.md table, ask the model to produce
     *  code for each row, append to logs/implement.log. Mirrors
     *  R236's runPhase4Dev. */
    private int runImplement(PhaseId phase, Path tasksPath, boolean auto,
                             LlmFn llm, ReplFn repl, Logger log) throws Exception {
        Path implLog = tasksPath.getParent().resolve("logs").resolve("implement.log");
        if (!Files.isRegularFile(tasksPath)) {
            log.log("[sdd] no tasks.md found at " + tasksPath + "; skipping implement");
            return 0;
        }
        String tasksBody = Files.readString(tasksPath, StandardCharsets.UTF_8);
        List<TaskRow> tasks = parseTasks(tasksBody);
        if (tasks.isEmpty()) {
            log.log("[sdd] WARN: no tasks parsed from " + tasksPath);
            Files.writeString(implLog,
                    "# implement.log\n\nNo tasks parsed from " + tasksPath + ".\n",
                    StandardCharsets.UTF_8);
            return 0;
        }
        log.log("[sdd] parsed " + tasks.size() + " tasks from " + tasksPath);
        StringBuilder out = new StringBuilder();
        int generated = 0;
        for (TaskRow t : tasks) {
            log.log("[sdd] --- Task " + t.id + " — " + t.title + " ---");
            if (!auto && repl instanceof InteractiveRepl ir) {
                String answer = ir.askClarify("task-" + t.id, "Task " + t.id,
                        "Approve task " + t.id + " (" + t.title + ")? Reply with feedback or empty to proceed.");
                if (answer != null && !answer.isBlank()) {
                    out.append("## ").append(t.id).append(" — ").append(t.title).append("\n\n")
                            .append("> reviewer feedback: ").append(answer).append("\n\n");
                }
            }
            String systemPrompt = "You are implementing a single task from a Spec Kit tasks.md. "
                    + "Output the code (and only the code) plus a 1-2 sentence note on how to verify it. "
                    + "Do not call tools; produce the patch in markdown fenced blocks.";
            String userPrompt = "Task: " + t.id + " — " + t.title
                    + "\nFiles: " + String.join(", ", t.files)
                    + "\nAcceptance: " + t.acceptance
                    + "\nEst: " + t.estMin + " min";
            String text = cleanOutput(llm.generate(systemPrompt, userPrompt, 2048));
            out.append("## ").append(t.id).append(" — ").append(t.title).append("\n\n")
                    .append(text).append("\n\n");
            Files.writeString(implLog, out.toString(), StandardCharsets.UTF_8);
            generated++;
        }
        log.log("[sdd] implement.log final: " + generated + " tasks → " + implLog);
        return generated;
    }

    /** Converge: loop the model until it reports "converged" or
     *  the user gives up. The NDJSON {@code converge-check} event
     *  carries the model's findings + the iteration count. */
    private int runConverge(PhaseId phase, Path featureDir, boolean auto,
                            LlmFn llm, ReplFn repl, Logger log) throws Exception {
        Path conv = featureDir.resolve("convergence.json");
        Path implLog = featureDir.resolve("logs").resolve("implement.log");
        String spec = readIfPresent(featureDir.resolve("spec.md"));
        String implSummary = readIfPresent(implLog);
        int iteration = 0;
        while (iteration < 5) {
            iteration++;
            String prompt = "You are doing a post-implementation review for an SDD feature.\n\n"
                    + "# spec.md\n" + spec + "\n\n# implement.log (tail)\n"
                    + (implSummary.length() > 4000 ? implSummary.substring(implSummary.length() - 4000) : implSummary)
                    + "\n\nOutput a JSON object with shape {converged: bool, issues: [string]}. "
                    + "Converged is true only if every FR in spec.md is covered by implement.log and there are no contradictions.";
            String response = cleanOutput(llm.generate(
                    "You are a post-implementation reviewer.",
                    prompt, 2048));
            boolean converged = CONVERGED_TRUE.matcher(response).find()
                    && !CONVERGED_FALSE.matcher(response).find();
            Files.writeString(conv, response, StandardCharsets.UTF_8);
            if (repl instanceof InteractiveRepl ir) {
                try { ir.emitConvergeCheck(phase, iteration, converged, response); } catch (Exception ignore) {}
            }
            if (converged || auto) {
                log.log("[sdd] converge: " + (converged ? "CONVERGED" : "skipped (auto)")
                        + " after " + iteration + " iteration(s)");
                return iteration;
            }
            if (!(repl instanceof InteractiveRepl ir2)) {
                log.log("[sdd] converge: non-interactive REPL; stopping after 1 iteration");
                return iteration;
            }
            String feedback = ir2.askConvergeIterate(iteration, response);
            if (feedback == null) {
                log.log("[sdd] converge: user aborted");
                throw new AbortException("user aborted at converge");
            }
            if (feedback.isBlank()) {
                log.log("[sdd] converge: user accepted not-converged; stopping");
                return iteration;
            }
            // Apply feedback: re-run implement with feedback appended.
            String feedbackPrompt = "Apply this feedback to the implementation:\n\n"
                    + feedback + "\n\n# implement.log\n" + implSummary;
            String revised = cleanOutput(llm.generate(
                    "You revise an implementation based on reviewer feedback.",
                    feedbackPrompt, 4096));
            Files.writeString(implLog, implSummary + "\n\n## converge iteration " + iteration + "\n\n"
                    + revised + "\n", StandardCharsets.UTF_8);
            implSummary = readIfPresent(implLog);
        }
        log.log("[sdd] converge: hit max iterations (5); marking incomplete");
        return iteration;
    }

    // ---- helpers ------------------------------------------------------

    private String buildSystemPrompt(PhaseId phase) {
        String tpl = config.phaseTemplate(phase);
        StringBuilder sb = new StringBuilder();
        if (!config.constitutionBody().isBlank()) {
            sb.append("# Project Constitution (binding)\n")
                    .append(config.constitutionBody())
                    .append("\n\n# Phase template: ").append(phase.specKitId()).append("\n")
                    .append(tpl);
        } else {
            sb.append(tpl);
        }
        if (!config.hardRules().isBlank()) {
            sb.append("\n\n").append(config.hardRules());
        }
        return sb.toString();
    }

    /** Build a revision prompt. The Spec Kit templates are not
     *  pre-split into initial/revision variants, so we wrap the
     *  template with a "Apply these revisions" prefix on revision
     *  rounds. The original template content is preserved below
     *  for context. */
    private String revisionPrompt(String template, String priorContent, String revision) {
        return "Apply these revisions to the prior artefact and re-emit the full revised markdown.\n\n"
                + "priorContent:\n```\n" + priorContent + "\n```\n\n"
                + "revisions:\n" + revision + "\n\n"
                + "Original phase guidance (for reference):\n" + template;
    }

    private static String readIfPresent(Path p) {
        if (p == null || !Files.isRegularFile(p)) return "";
        try { return Files.readString(p, StandardCharsets.UTF_8); } catch (IOException ioe) { return ""; }
    }

    /** Resolve the per-feature directory: {@code <cwd>/<artefactRoot>/specs/<slug>}. */
    public Path artefactDir(Path cwd, String slug) {
        Path root = cwd.resolve(config.artefactRoot()).resolve("specs");
        return root.resolve(slug);
    }

    /** {@code <cwd>/<artefactRoot>/memory/constitution.md} — single
     *  file shared across all features. */
    public Path constitutionPath(Path cwd) {
        Path root = cwd.resolve(config.artefactRoot()).resolve("memory");
        return root.resolve("constitution.md");
    }

    /** Strip {@code <think>…</think>} blocks, chatty preamble lines,
     *  and runs of 3+ blank lines. Mirrors the legacy
     *  {@code clean_markdown} in the old Python driver; behaviour
     *  is identical so existing artefacts reproduce byte-for-byte
     *  if a user migrates an old run. Public so the CLI (which
     *  does its own first-pass clean before handing text to the
     *  runner for the artefacts) can reuse the same trimming. */
    public static String cleanOutput(String text) {
        if (text == null || text.isBlank()) return "";
        String prev;
        do { prev = text; text = text.replaceAll("(?s)<think>.*?</think>", ""); }
        while (!text.equals(prev));
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (String line : lines) {
            if (!started) {
                String s = line.strip();
                if (s.isEmpty()) continue;
                if (s.startsWith("# ") || s.startsWith("## ") || s.startsWith("### ")
                        || s.startsWith("| ") || s.startsWith("```")) {
                    started = true;
                } else {
                    String lower = s.toLowerCase();
                    if (lower.startsWith("draft:") || lower.startsWith("note:")
                            || lower.startsWith("i will") || lower.startsWith("i'll")
                            || lower.startsWith("let me") || lower.startsWith("sure,")
                            || lower.startsWith("okay,") || lower.startsWith("here's")
                            || lower.startsWith("here is")) {
                        continue;
                    }
                    continue;
                }
            }
            sb.append(line).append('\n');
        }
        String result = sb.toString().strip();
        result = result.replaceAll("\n{3,}", "\n\n");
        if (result.isEmpty()) return "";
        return result + "\n";
    }

    /** One task row from tasks.md. {@code files} is a list of
     *  back-ticked-or-bare paths; the parser strips backticks
     *  defensively. */
    public record TaskRow(String id, String title, List<String> files, int estMin, String acceptance) {}

    /** Parse {@code | T-NNN | title | files | est | acceptance |}
     *  rows. Falls back to a checklist bullet list
     *  ({@code - [ ] TNNN description}), then to a numbered list,
     *  when the table is missing. */
    public static List<TaskRow> parseTasks(String body) {
        List<TaskRow> out = new ArrayList<>();
        if (body == null) return out;
        // First try the table form (Spec Kit default).
        Matcher m = TASK_ROW.matcher(body);
        while (m.find()) {
            String id = m.group(1).trim();
            String title = m.group(2).trim();
            String filesCell = m.group(3);
            int estMin;
            try { estMin = Integer.parseInt(m.group(4).trim()); }
            catch (NumberFormatException nfe) { estMin = 0; }
            String acceptance = m.group(5).trim();
            List<String> files = new ArrayList<>();
            for (String f : filesCell.split(",")) {
                String cleaned = f.trim();
                if (cleaned.startsWith("`") && cleaned.endsWith("`") && cleaned.length() >= 2) {
                    cleaned = cleaned.substring(1, cleaned.length() - 1);
                }
                if (!cleaned.isEmpty()) files.add(cleaned);
            }
            out.add(new TaskRow(id, title, files, estMin, acceptance));
        }
        if (out.isEmpty()) {
            // Fallback: checklist bullet (`- [ ] TNNN ...`).
            Matcher c = TASK_CHECKLIST.matcher(body);
            while (c.find()) {
                String id = c.group(1).trim();
                String rest = c.group(2).trim();
                // Strip leading [P] / [USn] markers; the description
                // is the remainder.
                rest = rest.replaceFirst("^\\s*\\[P\\]\\s*", "")
                           .replaceFirst("^\\s*\\[US\\d+\\]\\s*", "")
                           .trim();
                // No files / est / acceptance captured for the
                // checklist form; we still pass them through so the
                // implement phase prompt shows the full description.
                List<String> implicitFiles = extractBacktickedPaths(rest);
                out.add(new TaskRow(id, rest, implicitFiles, 0, ""));
            }
        }
        if (out.isEmpty()) {
            Matcher n = Pattern.compile("^\\s*(\\d+)\\.\\s+([^\\n]+)$", Pattern.MULTILINE).matcher(body);
            while (n.find()) {
                out.add(new TaskRow("T-fallback." + n.group(1),
                        n.group(2).trim(), List.of(), 0, "(no acceptance criterion parsed)"));
            }
        }
        return out;
    }

    /** Pull back-ticked paths out of a description string.
     *  Used by the checklist form so the implement phase
     *  prompt can name files when the user wrote them in
     *  prose. Returns {@code []} when there are none. */
    private static List<String> extractBacktickedPaths(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        Matcher m = Pattern.compile("`([^`]+)`").matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** LLM call. The runner passes the system prompt (constitution +
     *  hard rules + phase template), the rendered user prompt, and
     *  the max-tokens hint. */
    @FunctionalInterface
    public interface LlmFn {
        String generate(String systemPrompt, String userPrompt, int maxTokens) throws Exception;
    }

    /** Confirmation step. Returns:
     *  <ul>
     *    <li>{@code null} to abort the whole SDD run;</li>
     *    <li>{@code Optional.empty()} (or {@code Optional.of("")})
     *        to accept and proceed;</li>
     *    <li>{@code Optional.of("revision text")} to re-run the
     *        phase with the revision as extra context.</li>
     *  </ul>
     */
    @FunctionalInterface
    public interface ReplFn {
        Optional<String> confirm(SddConfig.PhaseId phase, Path artefactPath, String content) throws Exception;
    }

    /** Progress sink. CLI plugs in a stdout printer; tests plug in
     *  a recorder. */
    @FunctionalInterface
    public interface Logger {
        void log(String line);
        Logger STDOUT = line -> System.out.println(line);
    }

    /** One phase's outcome. {@code revisions} is the number of
     *  revision rounds (0 for the initial-only case). For the
     *  implement phase, {@code artefactPath} points at
     *  {@code logs/implement.log}; for clarify it points at
     *  {@code clarify.json}; for converge at
     *  {@code convergence.json}. */
    public record PhaseResult(String phaseId, Path artefactPath, int revisions) {}

    /** Thrown when the user aborts (NDJSON {@code quit} or EOF).
     *  CLI catches this and exits with code 2. */
    public static final class AbortException extends RuntimeException {
        public AbortException(String msg) { super(msg); }
    }

    /** Resolve the next slug for a feature under SEQUENTIAL /
     *  TIMESTAMP policy. Pure helper so the CLI can use it before
     *  the runner starts. */
    public String nextSlug(Path cwd, String featureName) {
        if (config.slugPolicy() == SlugPolicy.TIMESTAMP) {
            String ts = LocalDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            return ts + "-" + featureName;
        }
        Path specsRoot = cwd.resolve(config.artefactRoot()).resolve("specs");
        if (!Files.isDirectory(specsRoot)) return "001-" + featureName;
        int max = 0;
        try (var stream = Files.list(specsRoot)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                String name = child.getFileName().toString();
                int dash = name.indexOf('-');
                if (dash <= 0) continue;
                try {
                    int n = Integer.parseInt(name.substring(0, dash));
                    if (n > max) max = n;
                } catch (NumberFormatException ignore) {}
            }
        } catch (IOException ioe) {
            return "001-" + featureName;
        }
        return String.format("%03d-%s", max + 1, featureName);
    }
}