package org.aethercode.workflows.ssd;

import org.aethercode.workflows.ssd.SsdConfig.Phase;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R236 — the SSD orchestrator. Drives the 4 phases (spec / design /
 * tasks / dev) end-to-end with per-phase human-in-the-loop
 * confirmation. The runner has zero coupling to any specific LLM
 * transport: it calls {@link LlmFn} for the model and {@link ReplFn}
 * for the user. The CLI plugs in {@code AetherCodeEngine.query(...)}
 * + a stdin reader; tests plug in mocks.
 *
 * <h2>Per-phase contract</h2>
 * <p>For each phase the runner:
 * <ol>
 *   <li>checks whether {@code <cwd>/<artefactRoot>/<feature>/<file>}
 *       already exists; if it does and {@code force} is false, asks
 *       the user to keep, revise, or abort;</li>
 *   <li>builds the system + user prompt (initial or revision variant)
 *       by substituting {@code {{feature}}}, {@code {{intent}}},
 *       {@code {{priorContent}}}, etc.;</li>
 *   <li>calls {@link LlmFn#generate(String, String, int)} and
 *       runs {@link #cleanOutput(String)} on the response so
 *       {@code <think>…</think>} blocks and chatty preambles are
 *       stripped before the artefact is written;</li>
 *   <li>writes the artefact to disk;</li>
 *   <li>asks the user to accept or paste a revision. If a revision
 *       is given, loops back to (2) with the just-written artefact
 *       inlined as {@code priorContent}.</li>
 * </ol>
 *
 * <h2>Phase 4 (dev)</h2>
 * <p>The runner parses the task table from {@code tasks.md} and runs
 * each row through {@code LlmFn} with the per-task system + user
 * prompt. The result is appended to {@code dev.log}. This is a
 * single LLM call per task; the runner does <em>not</em> execute
 * the model's suggestions itself.
 */
public final class SsdRunner {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(SsdRunner.class);

    /** Marker for phase-4 task rows in tasks.md. The columns are
     *  {@code id | title | files | est_min | acceptance}, matching
     *  what the bundled phase-3 prompt template emits. The
     *  files column may be a back-ticked single path
     *  ({@code `path/to/Foo.java`}) or a comma-separated list of
     *  back-ticked paths ({@code `a.java`, `b.java`}), so the
     *  files-cell capture is generous: any non-{@code |} chars
     *  plus commas, with optional backticks on each path. */
    private static final Pattern TASK_ROW = Pattern.compile(
            "^\\|\\s*(T-\\d+\\.\\d+\\.\\d+)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*(\\d+)\\s*\\|\\s*([^|]+?)\\s*\\|",
            Pattern.MULTILINE);

    private final SsdConfig config;

    public SsdRunner(SsdConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Run every phase in {@code config.orderedPhases()} whose
     *  {@code order} falls in {@code [fromPhase, toPhase]}.
     *  Returns one {@link PhaseResult} per phase that produced
     *  an artefact (revised phases still produce a single
     *  result; the revision count is the
     *  {@link PhaseResult#revisions()} field). */
    public List<PhaseResult> runAll(Path cwd,
                                    String feature,
                                    String intent,
                                    int fromPhase,
                                    boolean force,
                                    boolean auto,
                                    LlmFn llm,
                                    ReplFn repl,
                                    Logger log,
                                    int toPhase) throws Exception {
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(llm, "llm");
        Objects.requireNonNull(repl, "repl");
        Objects.requireNonNull(log, "log");
        if (fromPhase < 1 || fromPhase > 4) {
            throw new IllegalArgumentException("fromPhase must be 1..4, got " + fromPhase);
        }
        if (toPhase < 1 || toPhase > 4) {
            throw new IllegalArgumentException("toPhase must be 1..4, got " + toPhase);
        }
        if (fromPhase > toPhase) {
            throw new IllegalArgumentException(
                    "fromPhase (" + fromPhase + ") must be <= toPhase (" + toPhase + ")");
        }

        Path featureDir = artefactDir(cwd, feature);
        Files.createDirectories(featureDir);
        log.log("[ssd] feature dir: " + featureDir);

        List<PhaseResult> results = new ArrayList<>();
        List<Phase> ordered = config.orderedPhases();

        // Phase 1-3: a shared context map is built up so later
        // phases can see the prior artefacts inline. We also keep
        // the on-disk Path so the next phase can confirm the file
        // exists before reading it.
        Map<String, Path> writtenPaths = new LinkedHashMap<>();
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("feature", feature);
        bodies.put("intent", intent);
        bodies.put("cwd", cwd.toString());

        for (Phase phase : ordered) {
            if (phase.order() < fromPhase) {
                log.log("[ssd] skipping phase " + phase.order() + " (" + phase.id() + ") — --from-phase=" + fromPhase);
                continue;
            }
            if (phase.order() > toPhase) {
                log.log("[ssd] stopping at phase " + toPhase + " (" + phase.id() + " skipped) — --to-phase=" + toPhase);
                break;
            }
            if (phase.order() == 4) {
                // Phase 4 is special — it doesn't write a single
                // artefact, it iterates the tasks in tasks.md.
                // We prefer the just-written path from this run
                // (so --force on phase 3 is honoured), but fall
                // back to the on-disk tasks.md when the user
                // started with --from-phase 4 and didn't re-run
                // phase 3.
                Path tasksPath = writtenPaths.get("tasks");
                if (tasksPath == null) {
                    tasksPath = featureDir.resolve("tasks.md");
                }
                if (!Files.isRegularFile(tasksPath)) {
                    log.log("[ssd] no tasks.md found at " + tasksPath + "; skipping dev phase");
                    continue;
                }
                int revisions = runPhase4Dev(phase, cwd, feature, intent, tasksPath, auto, llm, repl, log);
                results.add(new PhaseResult(phase.id(), tasksPath.getParent().resolve("dev.log"), revisions));
                continue;
            }

            Path outFile = featureDir.resolve(phase.file());
            bodies.put("priorContent", bodies.getOrDefault(phase.id() + ".content", ""));
            // For design / tasks we want the previous artefact
            // available even when the user did NOT revise the
            // current one (e.g. spec.md is the priorContent for
            // design.md). We expose each artefact under both its
            // own key and the canonical priorContent slot.
            bodies.put("specContent", bodies.getOrDefault("spec.content", ""));
            bodies.put("designContent", bodies.getOrDefault("design.content", ""));

            // If the file already exists and we're not forcing,
            // ask the user first. If the user types a revision,
            // we fall through to the generation path with that
            // revision in the bag.
            String revision = null;
            if (Files.isRegularFile(outFile) && !force) {
                String existing = Files.readString(outFile, StandardCharsets.UTF_8);
                log.log("[ssd] " + phase.file() + " already exists (" + existing.length() + " chars); asking");
                Optional<String> reply = repl.confirm(phase.title(), outFile, existing);
                if (reply == null) {
                    throw new AbortException("user aborted at existing " + phase.file());
                }
                if (reply.isPresent() && !reply.get().isBlank()) {
                    revision = reply.get();
                } else {
                    log.log("[ssd] reusing existing " + phase.file());
                    bodies.put(phase.id() + ".content", existing);
                    bodies.put("priorContent", existing);
                    writtenPaths.put(phase.id(), outFile);
                    results.add(new PhaseResult(phase.id(), outFile, 0));
                    continue;
                }
            }

            int revisionsThisPhase = 0;
            String lastContent = "";
            // Generation loop: run, write, ask user, if revision
            // -> re-run with the revision. Exit on accept or abort.
            while (true) {
                Map<String, Object> bag = new LinkedHashMap<>(bodies);
                if (revision != null) {
                    bag.put("revision", revision);
                    bag.put("priorContent", lastContent);
                    String systemPrompt = phase.systemPrompt();
                    String userPrompt = phase.renderRevisionPrompt(bag);
                    log.log("[ssd] phase " + phase.order() + " (revision #" + (revisionsThisPhase + 1) + ")");
                    String text = cleanOutput(llm.generate(systemPrompt, userPrompt, phase.maxTokens()));
                    Files.writeString(outFile, text, StandardCharsets.UTF_8);
                    log.log("[ssd] wrote " + outFile + " (" + text.length() + " chars)");
                    lastContent = text;
                    revisionsThisPhase++;
                } else {
                    String systemPrompt = phase.systemPrompt();
                    String userPrompt = phase.renderUserPrompt(bag);
                    log.log("[ssd] phase " + phase.order() + " (initial)");
                    String text = cleanOutput(llm.generate(systemPrompt, userPrompt, phase.maxTokens()));
                    Files.writeString(outFile, text, StandardCharsets.UTF_8);
                    log.log("[ssd] wrote " + outFile + " (" + text.length() + " chars)");
                    lastContent = text;
                }

                if (auto) {
                    log.log("[ssd] auto-accept on " + phase.file());
                    break;
                }
                Optional<String> reply = repl.confirm(phase.title(), outFile, lastContent);
                if (reply == null) {
                    throw new AbortException("user aborted at " + phase.file());
                }
                if (reply.isEmpty() || reply.get().isBlank()) {
                    log.log("[ssd] accepted " + phase.file());
                    break;
                }
                revision = reply.get();
            }

            bodies.put(phase.id() + ".content", lastContent);
            bodies.put("priorContent", lastContent);
            writtenPaths.put(phase.id(), outFile);
            results.add(new PhaseResult(phase.id(), outFile, revisionsThisPhase));
        }
        return results;
    }

    /** Phase 4: walk the task table and ask the LLM to implement
     *  each row. Returns the number of tasks actually generated
     *  (skipped tasks are not counted). */
    private int runPhase4Dev(Phase phase, Path cwd, String feature, String intent,
                             Path tasksPath, boolean auto,
                             LlmFn llm, ReplFn repl, Logger log) throws Exception {
        Path devLog = tasksPath.getParent().resolve("dev.log");
        String tasksBody = Files.readString(tasksPath, StandardCharsets.UTF_8);
        List<TaskRow> tasks = parseTasks(tasksBody);
        if (tasks.isEmpty()) {
            log.log("[ssd] WARN: no tasks parsed from " + tasksPath + " — writing empty dev.log");
            Files.writeString(devLog,
                    "# dev.log\n\nNo tasks parsed from " + tasksPath + ".\n",
                    StandardCharsets.UTF_8);
            return 0;
        }
        log.log("[ssd] parsed " + tasks.size() + " tasks from " + tasksPath);

        StringBuilder out = new StringBuilder();
        int generated = 0;
        for (TaskRow t : tasks) {
            log.log("[ssd] --- Task " + t.id + " — " + t.title + " ---");
            log.log("[ssd]     files: " + String.join(", ", t.files));
            log.log("[ssd]     acceptance: " + t.acceptance);
            if (!auto) {
                Optional<String> reply = repl.confirm("Task " + t.id,
                        devLog, "implement " + t.id + " — " + t.title);
                if (reply == null) {
                    out.append("## ").append(t.id).append(" — skipped\n\n");
                    continue;
                }
                // Empty / blank reply = proceed. Non-blank reply
                // = revision; we honour it by appending to the
                // user prompt. (We don't re-run the task because
                // the user said something like "remember also
                // cover error path"; we let the model see it as
                // extra context.)
            }
            Map<String, Object> bag = new LinkedHashMap<>();
            bag.put("feature", feature);
            bag.put("intent", intent);
            bag.put("taskId", t.id);
            bag.put("taskTitle", t.title);
            bag.put("taskFiles", String.join(", ", t.files));
            bag.put("taskEstMin", String.valueOf(t.estMin));
            bag.put("taskAcceptance", t.acceptance);
            bag.put("cwd", cwd.toString());
            String userPrompt = phase.renderUserPrompt(bag);
            String text = cleanOutput(llm.generate(phase.systemPrompt(), userPrompt, phase.maxTokens()));
            out.append("## ").append(t.id).append(" — ").append(t.title).append("\n\n")
                    .append(text).append("\n\n");
            // Write incrementally so a crash mid-phase keeps the
            // work-to-date visible in the artefact.
            Files.writeString(devLog, out.toString(), StandardCharsets.UTF_8);
            generated++;
        }
        log.log("[ssd] dev.log final: " + generated + " tasks written to " + devLog);
        return generated;
    }

    /** One row of the tasks.md table. {@code files} is a list of
     *  back-ticked-or-bare paths; the parser strips backticks
     *  defensively. */
    record TaskRow(String id, String title, List<String> files, int estMin, String acceptance) {}

    /** Parse {@code | T-N.M.K | title | files | est | acceptance |}
     *  rows. Falls back to a numbered list (1. / 2. / …) when the
     *  table is malformed. */
    static List<TaskRow> parseTasks(String body) {
        List<TaskRow> out = new ArrayList<>();
        if (body == null) return out;
        Matcher m = TASK_ROW.matcher(body);
        while (m.find()) {
            String id = m.group(1).trim();
            String title = m.group(2).trim();
            String filesCell = m.group(3);
            int estMin;
            try {
                estMin = Integer.parseInt(m.group(4).trim());
            } catch (NumberFormatException nfe) {
                estMin = 0;
            }
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
            // Fallback: numbered list
            Matcher n = Pattern.compile("^\\s*(\\d+)\\.\\s+([^\\n]+)$", Pattern.MULTILINE).matcher(body);
            while (n.find()) {
                out.add(new TaskRow("T-fallback." + n.group(1),
                        n.group(2).trim(), List.of(), 0,
                        "(no acceptance criterion parsed)"));
            }
        }
        return out;
    }

    /** Strip {@code <think>…</think>} blocks, chatty preamble lines,
     *  and runs of 3+ blank lines. Mirrors the legacy
     *  {@code clean_markdown} in the old Python driver; the
     *  behaviour is identical so existing artefacts reproduce
     *  byte-for-byte.
     *
     *  <p>Public so the CLI (which does its own first-pass
     *  clean before handing text to the runner for the
     *  spec/design/tasks/dev artefacts) can reuse the same
     *  trimming logic without duplicating the regex soup. */
    public static String cleanOutput(String text) {
        if (text == null || text.isBlank()) return "";
        // 1) remove every <think>...</think> block (incl. nested via iteration)
        String prev;
        do {
            prev = text;
            text = text.replaceAll("(?s)<think>.*?</think>", "");
        } while (!text.equals(prev));
        // 2) drop chatty preamble until we hit a markdown header / table / code fence
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (String line : lines) {
            if (!started) {
                String s = line.strip();
                if (s.isEmpty()) continue;
                if (s.startsWith("# ") || s.startsWith("## ")
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
        // 3) collapse 3+ blank lines into 1
        String result = sb.toString().strip();
        result = result.replaceAll("\n{3,}", "\n\n");
        if (result.isEmpty()) return "";
        return result + "\n";
    }

    /** Resolve the per-feature directory:
     *  {@code <cwd>/<artefactRoot>/<feature>}. The default
     *  {@code artefactRoot} is {@code .aethercode/ssd}, so
     *  the typical layout is {@code .aethercode/ssd/<feature>/}. */
    public Path artefactDir(Path cwd, String feature) {
        Path root = cwd.resolve(config.artefactRoot());
        return root.resolve(feature);
    }

    /** LLM call. The runner passes the system prompt, the rendered
     *  user prompt, and the per-phase max-tokens hint. The
     *  implementation is responsible for actually calling the
     *  model, streaming the response, and returning the final
     *  text. {@link SsdRunner#cleanOutput(String)} runs over the
     *  result, so the implementation can return raw output. */
    @FunctionalInterface
    public interface LlmFn {
        String generate(String systemPrompt, String userPrompt, int maxTokens) throws Exception;
    }

    /** Confirmation step. Returns:
     *  <ul>
     *    <li>{@code null} to abort the whole SSD run (the runner
     *        throws {@link AbortException} so the CLI can exit
     *        with a non-zero code);</li>
     *    <li>{@code Optional.empty()} OR {@code Optional.of("")}
     *        to accept and proceed (the empty-string case is
     *        what a plain "Enter" maps to in the legacy
     *        driver);</li>
     *    <li>{@code Optional.of("revision text")} to re-run the
     *        phase with the revision as extra context.</li>
     *  </ul>
     */
    @FunctionalInterface
    public interface ReplFn {
        Optional<String> confirm(String phaseTitle, Path artefactPath, String content) throws Exception;
    }

    /** Progress sink. The CLI plugs in a stdout printer; tests
     *  plug in a recorder. */
    @FunctionalInterface
    public interface Logger {
        void log(String line);
        Logger STDOUT = line -> System.out.println(line);
    }

    /** One phase's outcome. {@code revisions} is the number of
     *  revision rounds (0 for the initial-only case). The
     *  {@code artefactPath} points at the file the runner wrote
     *  (or, for phase 4, at {@code dev.log}). */
    public record PhaseResult(String phaseId, Path artefactPath, int revisions) {}

    /** Thrown when the user aborts (e.g. types {@code q} in the
     *  REPL). The CLI catches this and exits with code 2. */
    public static final class AbortException extends RuntimeException {
        public AbortException(String msg) { super(msg); }
    }
}
