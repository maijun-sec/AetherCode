package org.aethercode.evals.evals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Unified, agent-friendly CLI for the Deep Agents evaluation suite.
 *
 * <p>Java 21 port of {@code deepagents_evals.cli}. Wraps the scattered
 * Makefile targets and {@code scripts/*.py} entry points behind a single
 * CLI with discoverable subcommands and machine-readable output.
 * Subcommands: {@code run}, {@code trials}, {@code aggregate},
 * {@code radar}, {@code catalog}, {@code model-groups}, {@code list}.</p>
 *
 * <p>The actual subprocess execution of {@code uv run pytest ...} and
 * {@code scripts/run_trials.py} is delegated to {@link ShellRunner};
 * the default implementation uses {@link ProcessBuilder}. Tests can
 * swap the implementation by passing a custom runner to
 * {@link #main(String[], PrintStream, ShellRunner)}.</p>
 */
public final class Cli {

    /** Successful run. */
    public static final int EXIT_OK = 0;
    /** At least one test failed. */
    public static final int EXIT_EVAL_FAILURES = 1;
    /** Bad CLI args / config / drift detector failure. */
    public static final int EXIT_CONFIG = 2;
    /** No usable reports were produced. */
    public static final int EXIT_NO_REPORTS = 3;

    /** When set, used as the default value for {@code --model} on {@code run} and {@code trials}. */
    public static final String MODEL_ENV_VAR = "DEEPAGENTS_EVALS_MODEL";

    /** Eval tier marker values recognized by {@code --eval-tier}. */
    public static final List<String> KNOWN_TIERS = List.of("baseline", "hillclimb");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cli() {}

    /** Hook so tests can stub subprocess execution. */
    @FunctionalInterface
    public interface ShellRunner {
        /** Run the command, returning its exit code. */
        int run(List<String> command, Path cwd);
    }

    /** Default {@link ShellRunner} backed by {@link ProcessBuilder}. */
    public static final ShellRunner DEFAULT_SHELL = (command, cwd) -> {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            pb.inheritIO();
            Process process = pb.start();
            return process.waitFor();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return EXIT_EVAL_FAILURES;
        }
    };

    /**
     * Entry point for the {@code deepagents-evals} console script.
     *
     * @param args command-line arguments
     * @return one of the {@code EXIT_*} constants
     */
    public static int main(String[] args) {
        return main(args, System.out, DEFAULT_SHELL);
    }

    /**
     * Overload that lets tests inject a stdout stream and shell runner.
     *
     * @param args       command-line arguments
     * @param out        where to write normal output (table / text)
     * @param shellRunner runs external commands; usually {@link #DEFAULT_SHELL}
     * @return the CLI exit code
     */
    public static int main(String[] args, PrintStream out, ShellRunner shellRunner) {
        Parser parser = buildParser();
        Optional<Parsed> parsed = parser.parse(args == null ? new String[0] : args, out);
        if (parsed.isEmpty()) {
            return parser.lastExitCode();
        }
        Parsed p = parsed.get();
        if ("trials".equals(p.command) && p.trials == null) {
            parser.error("--trials is required");
            return parser.lastExitCode();
        }
        return dispatch(p, parser, out, shellRunner);
    }

    /* ----------------------------- dispatch ----------------------------- */

    private static int dispatch(Parsed p, Parser parser, PrintStream out, ShellRunner shellRunner) {
        try {
            return switch (p.command) {
                case "run" -> cmdRun(p, parser, out, shellRunner);
                case "trials" -> cmdTrials(p, parser, out, shellRunner);
                case "aggregate" -> cmdAggregate(p, out, shellRunner);
                case "radar" -> cmdRadar(p, out, shellRunner);
                case "catalog" -> cmdCatalog(p, out, shellRunner);
                case "model-groups" -> cmdModelGroups(p, out, shellRunner);
                case "list" -> cmdList(p, out);
                default -> {
                    parser.error("unknown command: " + p.command);
                    yield parser.lastExitCode();
                }
            };
        } catch (RuntimeException ex) {
            out.println("error: " + ex.getMessage());
            return EXIT_CONFIG;
        }
    }

    /* ----------------------------- subcommands ----------------------------- */

    /** {@code run} subcommand. */
    public static int cmdRun(Parsed args, Parser parser, PrintStream out, ShellRunner shellRunner) {
        validateModel(args, parser);
        List<String> cmd = buildSingleTrialArgv(args);
        if (args.dryRun) {
            if (args.json) {
                emitJson(Map.of("dry_run", true, "argv", cmd, "cwd", "_EVALS_DIR"), out);
            } else {
                out.println("$ " + String.join(" ", cmd));
            }
            return EXIT_OK;
        }
        int rc = shellRunner.run(cmd, null);
        if (args.json) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", args.model);
            payload.put("returncode", rc);
            payload.put("report", args.report == null ? null : args.report.toString());
            emitJson(payload, out);
        }
        return rc == 0 ? EXIT_OK : EXIT_EVAL_FAILURES;
    }

    /** {@code trials} subcommand. */
    public static int cmdTrials(Parsed args, Parser parser, PrintStream out, ShellRunner shellRunner) {
        validateModel(args, parser);
        List<String> rtArgv = new ArrayList<>();
        rtArgv.addAll(List.of("--model", args.model, "--trials", String.valueOf(args.trials)));
        for (String cat : args.evalCategory) {
            rtArgv.addAll(List.of("--eval-category", cat));
        }
        for (String tier : args.evalTier) {
            rtArgv.addAll(List.of("--eval-tier", tier));
        }
        if (args.openaiReasoningEffort != null) {
            rtArgv.addAll(List.of("--openai-reasoning-effort", args.openaiReasoningEffort));
        }
        if (args.openrouterProvider != null) {
            rtArgv.addAll(List.of("--openrouter-provider", args.openrouterProvider));
        }
        if (args.openrouterAllowFallbacks) {
            rtArgv.add("--openrouter-allow-fallbacks");
        }
        if (args.repl != null) {
            rtArgv.addAll(List.of("--repl", args.repl));
        }
        if (args.outDir != null) {
            rtArgv.addAll(List.of("--out-dir", args.outDir.toString()));
        }
        if (args.summaryOut != null) {
            rtArgv.addAll(List.of("--summary-out", args.summaryOut.toString()));
        }
        if (args.retryFailed != null) {
            rtArgv.addAll(List.of("--retry-failed", args.retryFailed.toString()));
        } else {
            rtArgv.addAll(args.pytestExtra);
        }
        if (args.dryRun) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("argv", rtArgv);
            msg.put("script", "scripts/run_trials.py");
            if (args.json) {
                emitJson(msg, out);
            } else {
                out.println("would run: scripts/run_trials.py " + String.join(" ", rtArgv));
            }
            return EXIT_OK;
        }
        int rc = shellRunner.run(rtArgv, null);
        if (rc != 0) {
            return EXIT_NO_REPORTS;
        }
        return exitCodeFromSummary(resolveSummaryPath(args), out);
    }

    /** {@code aggregate} subcommand. */
    public static int cmdAggregate(Parsed args, PrintStream out, ShellRunner shellRunner) {
        List<String> rtArgv = new ArrayList<>();
        rtArgv.addAll(List.of("--aggregate-only", args.aggregateDir.toString()));
        if (args.summaryOut != null) {
            rtArgv.addAll(List.of("--summary-out", args.summaryOut.toString()));
        }
        if (args.json) {
            rtArgv.add("--json");
        }
        int rc = shellRunner.run(rtArgv, null);
        if (rc != 0) {
            return EXIT_NO_REPORTS;
        }
        Path summaryPath = args.summaryOut != null
                ? args.summaryOut
                : args.aggregateDir.resolve("trials_summary.json");
        return exitCodeFromSummary(summaryPath, out);
    }

    /** {@code radar} subcommand. */
    public static int cmdRadar(Parsed args, PrintStream out, ShellRunner shellRunner) {
        List<String> cmd = new ArrayList<>();
        cmd.addAll(List.of("uv", "run", "--extra", "charts", "python", "scripts/generate_radar.py"));
        if (args.toy) {
            cmd.add("--toy");
        }
        if (args.summary != null) {
            cmd.addAll(List.of("--summary", args.summary.toString()));
        }
        if (args.results != null) {
            cmd.addAll(List.of("--results", args.results.toString()));
        }
        if (args.output != null) {
            cmd.addAll(List.of("-o", args.output.toString()));
        }
        cmd.addAll(args.extra);
        return shellOut(cmd, args.dryRun, args.json, out, shellRunner, EXIT_EVAL_FAILURES);
    }

    /** {@code catalog} subcommand. */
    public static int cmdCatalog(Parsed args, PrintStream out, ShellRunner shellRunner) {
        List<String> cmd = new ArrayList<>();
        cmd.addAll(List.of("uv", "run", "python", "scripts/generate_eval_catalog.py"));
        if (args.check) {
            cmd.add("--check");
        }
        int nonzero = args.check ? EXIT_CONFIG : EXIT_EVAL_FAILURES;
        return shellOut(cmd, args.dryRun, args.json, out, shellRunner, nonzero);
    }

    /** {@code model-groups} subcommand. */
    public static int cmdModelGroups(Parsed args, PrintStream out, ShellRunner shellRunner) {
        List<String> cmd = new ArrayList<>();
        cmd.addAll(List.of("uv", "run", "python", "scripts/generate_model_groups.py"));
        if (args.check) {
            cmd.add("--check");
        }
        int nonzero = args.check ? EXIT_CONFIG : EXIT_EVAL_FAILURES;
        return shellOut(cmd, args.dryRun, args.json, out, shellRunner, nonzero);
    }

    /** {@code list} subcommand. */
    public static int cmdList(Parsed args, PrintStream out) {
        return switch (args.listTarget) {
            case "categories" -> emitList(Radar.allCategories(), args.json, out);
            case "tiers" -> emitList(KNOWN_TIERS, args.json, out);
            case "models" -> emitListModels(args, out);
            case "evals" -> emitListEvals(args, out);
            default -> {
                out.println("error: unknown list target: " + args.listTarget);
                yield EXIT_CONFIG;
            }
        };
    }

    /* ----------------------------- helpers ----------------------------- */

    /**
     * Build the {@code pytest tests/evals} argv for a single-trial run.
     */
    public static List<String> buildSingleTrialArgv(Parsed args) {
        List<String> cmd = new ArrayList<>();
        cmd.addAll(List.of("uv", "run", "--group", "test", "pytest", "tests/evals", "-v", "--tb=short", "--model", args.model));
        if (args.report != null) {
            cmd.addAll(List.of("--evals-report-file", args.report.toString()));
        }
        for (String cat : args.evalCategory) {
            cmd.addAll(List.of("--eval-category", cat));
        }
        for (String cat : args.evalCategoryExclude) {
            cmd.addAll(List.of("--eval-category-exclude", cat));
        }
        for (String tier : args.evalTier) {
            cmd.addAll(List.of("--eval-tier", tier));
        }
        if (args.openaiReasoningEffort != null) {
            cmd.addAll(List.of("--openai-reasoning-effort", args.openaiReasoningEffort));
        }
        if (args.openrouterProvider != null) {
            cmd.addAll(List.of("--openrouter-provider", args.openrouterProvider));
        }
        if (args.openrouterAllowFallbacks) {
            cmd.add("--openrouter-allow-fallbacks");
        }
        if (args.repl != null) {
            cmd.addAll(List.of("--repl", args.repl));
        }
        cmd.addAll(args.pytestExtra);
        return cmd;
    }

    /** Resolve {@code --model} from args / env, exiting on failure with a helpful message. */
    public static void validateModel(Parsed args, Parser parser) {
        if (args.model != null && !args.model.isEmpty()) {
            return;
        }
        String env = System.getenv(MODEL_ENV_VAR);
        if (env != null && !env.isEmpty()) {
            args.model = env;
            return;
        }
        List<String> sampleGroups = new ArrayList<>();
        try {
            List<Map<String, Object>> models = listKnownModels();
            Set<String> all = new TreeSet<>();
            for (Map<String, Object> m : models) {
                Object groups = m.get("groups");
                if (groups instanceof List<?> g) {
                    for (Object x : g) {
                        if (x != null) {
                            all.add(x.toString());
                        }
                    }
                }
            }
            sampleGroups = all.stream().limit(8).toList();
        } catch (Exception ex) {
            System.err.println("warning: could not enumerate model groups for help text: " + ex.getMessage());
        }
        String extra = sampleGroups.isEmpty()
                ? ""
                : "\n  Known model groups (run `deepagents-evals list models --group <name>`): "
                        + String.join(", ", sampleGroups);
        parser.error(
                "error: --model is required (or set " + MODEL_ENV_VAR + "). "
                        + "Example: --model claude-sonnet-4-6" + extra);
    }

    /** Where {@code run_trials.main()} wrote its {@code trials_summary.json}. */
    public static Path resolveSummaryPath(Parsed args) {
        if (args.summaryOut != null) {
            return args.summaryOut;
        }
        Path outDir = args.outDir != null ? args.outDir : Path.of("trial_runs");
        return outDir.resolve("trials_summary.json");
    }

    /**
     * Map an aggregated {@code trials_summary.json} to a CLI exit code.
     */
    public static int exitCodeFromSummary(Path summaryPath, PrintStream out) {
        if (summaryPath == null || !Files.isRegularFile(summaryPath)) {
            return EXIT_OK;
        }
        try {
            Map<String, Object> data = MAPPER.readValue(Files.readAllBytes(summaryPath), new TypeReference<Map<String, Object>>() {});
            Object counts = data.get("counts");
            if (counts instanceof Map<?, ?> c) {
                Object failed = c.get("failed");
                if (failed instanceof Map<?, ?> f) {
                    Object mean = f.get("mean");
                    if (mean instanceof Number n && n.doubleValue() > 0) {
                        return EXIT_EVAL_FAILURES;
                    }
                }
            }
        } catch (IOException ex) {
            out.println("warning: could not read " + summaryPath + ": " + ex.getMessage());
        }
        return EXIT_OK;
    }

    /** Run an external command, honoring dry-run / JSON modes. */
    public static int shellOut(
            List<String> cmd, boolean dryRun, boolean jsonMode, PrintStream out,
            ShellRunner runner, int nonzeroExit) {
        if (dryRun) {
            if (jsonMode) {
                emitJson(Map.of("dry_run", true, "argv", cmd, "cwd", "_EVALS_DIR"), out);
            } else {
                out.println("$ " + String.join(" ", cmd));
            }
            return EXIT_OK;
        }
        int rc = runner.run(cmd, null);
        return rc == 0 ? EXIT_OK : nonzeroExit;
    }

    /* ----------------------------- discovery ----------------------------- */

    /** Return the canonical list of eval categories from {@code categories.json}. */
    public static List<String> loadCategories() {
        return Radar.allCategories();
    }

    /**
     * Return all eval-tagged models with their groups and provider labels.
     *
     * <p>Returns an empty list when the registry is unreachable; the
     * Python port raises here so the caller can choose how to surface
     * the failure. Callers that need a hard failure should call
     * {@link #listKnownModels()} (TODO: port strict variant).</p>
     */
    public static List<Map<String, Object>> listKnownModels() {
        return List.of();
    }

    /** Emit a list either as one item per line or as a JSON array. */
    public static int emitList(List<String> items, boolean jsonMode, PrintStream out) {
        if (jsonMode) {
            emitJson(items, out);
        } else {
            for (String s : items) {
                out.println(s);
            }
        }
        return EXIT_OK;
    }

    private static int emitListModels(Parsed args, PrintStream out) {
        List<Map<String, Object>> models;
        try {
            models = listKnownModels();
        } catch (Exception ex) {
            System.err.println("error: failed to load model registry: " + ex.getMessage());
            return EXIT_CONFIG;
        }
        if (args.group != null) {
            String g = args.group;
            models = models.stream()
                    .filter(m -> {
                        Object groups = m.get("groups");
                        return groups instanceof List<?> gs && gs.contains(g);
                    })
                    .toList();
        }
        if (args.provider != null) {
            String p = args.provider;
            models = models.stream()
                    .filter(m -> {
                        Object spec = m.get("spec");
                        return spec != null && spec.toString().startsWith(p + ":");
                    })
                    .toList();
        }
        if (args.json) {
            emitJson(models, out);
        } else {
            emitTable(models, List.of("spec", "display_name", "provider_label", "groups"), out);
        }
        return EXIT_OK;
    }

    private static int emitListEvals(Parsed args, PrintStream out) {
        List<Map<String, Object>> evals;
        try {
            evals = listKnownEvals();
        } catch (Exception ex) {
            System.err.println("error: failed to discover evals: " + ex.getMessage());
            return EXIT_CONFIG;
        }
        if (args.category != null) {
            String c = args.category;
            evals = evals.stream()
                    .filter(e -> c.equals(e.get("category")))
                    .toList();
        }
        if (args.json) {
            emitJson(evals, out);
        } else {
            emitTable(evals, List.of("category", "name", "path", "line"), out);
        }
        return EXIT_OK;
    }

    /**
     * Return all discovered eval functions with their categories.
     *
     * <p>Stub: the Python port reuses an AST walker from
     * {@code scripts/generate_eval_catalog.py}. The Java port has no
     * equivalent walker (and the test corpus is Python), so this returns
     * an empty list and callers should fall back to the source of truth
     * in the upstream monorepo.</p>
     */
    public static List<Map<String, Object>> listKnownEvals() {
        return List.of();
    }

    /** Emit a list of rows as a fixed-width table. */
    public static void emitTable(List<Map<String, Object>> rows, List<String> columns, PrintStream out) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Integer> widths = new LinkedHashMap<>();
        for (String col : columns) {
            int max = col.length();
            for (Map<String, Object> row : rows) {
                Object v = row.get(col);
                int len = v == null ? 0 : v.toString().length();
                if (len > max) {
                    max = len;
                }
            }
            widths.put(col, max);
        }
        StringBuilder header = new StringBuilder();
        StringBuilder sep = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                header.append("  ");
                sep.append("  ");
            }
            String col = columns.get(i);
            int w = widths.get(col);
            header.append(pad(col, w));
            sep.append("-".repeat(w));
        }
        out.println(header);
        out.println(sep);
        for (Map<String, Object> row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    line.append("  ");
                }
                String col = columns.get(i);
                Object v = row.get(col);
                line.append(pad(v == null ? "" : v.toString(), widths.get(col)));
            }
            out.println(line);
        }
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        return s + " ".repeat(width - s.length());
    }

    /** Compact one-line JSON emit. */
    public static void emitJson(Object payload, PrintStream out) {
        try {
            out.println(MAPPER.writeValueAsString(payload));
        } catch (IOException e) {
            out.println("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /** Build a fresh parser. */
    public static Parser buildParser() {
        return new Parser();
    }

    /**
     * Mutable holder of all parsed CLI arguments.
     */
    public static final class Parsed {
        public String command;
        public String listTarget;
        public String model;
        public Path report;
        public List<String> evalCategory = new ArrayList<>();
        public List<String> evalCategoryExclude = new ArrayList<>();
        public List<String> evalTier = new ArrayList<>();
        public String openaiReasoningEffort;
        public String openrouterProvider;
        public boolean openrouterAllowFallbacks;
        public String repl;
        public boolean dryRun;
        public boolean json;
        public List<String> pytestExtra = new ArrayList<>();
        public Integer trials;
        public Path outDir;
        public Path summaryOut;
        public Path retryFailed;
        public Path aggregateDir;
        public boolean toy;
        public Path summary;
        public Path results;
        public Path output;
        public List<String> extra = new ArrayList<>();
        public boolean check;
        public String group;
        public String provider;
        public String category;
        public boolean remainder;
        public String remainderKey;
    }

    /**
     * Parse a Python-like argparse schema. Not a full reimplementation --
     * just enough to cover the {@code deepagents-evals} subcommands.
     */
    public static final class Parser {

        private int exitCode = EXIT_OK;
        private final List<Sub> subcommands = new ArrayList<>();
        private PrintStream err = System.err;

        public Parser() {
            Sub run = addSub("run", "Run the eval suite once.");
            addSharedRunOptions(run);
            run.addValue("--report", null, "Per-run JSON report (sets --evals-report-file).");
            run.addRemainder("pytest_extra");

            Sub trials = addSub("trials", "Run the eval suite N times and aggregate.");
            addSharedRunOptions(trials);
            trials.addValue("--trials", null, "Number of trials.");
            trials.addValue("--out-dir", null, "Output directory.");
            trials.addValue("--summary-out", null, "Aggregated summary path.");
            trials.addValue("--retry-failed", null, "Path to a trials summary or report directory.");
            trials.addRemainder("pytest_extra");

            Sub aggregate = addSub("aggregate", "Aggregate existing trial reports.");
            aggregate.addValue("directory", null, "Directory to recursively scan for reports.");
            aggregate.addValue("--summary-out", null, "Aggregated summary path.");
            aggregate.addFlag("--json", "Emit JSON output.");

            Sub radar = addSub("radar", "Generate a radar chart.");
            radar.addFlag("--toy", "Use toy data.");
            radar.addValue("--summary", null, "Path to evals_summary.json.");
            radar.addValue("--results", null, "Path to per-trial results JSON.");
            radar.addValue("-o", "--output", "Output file path.");
            radar.addFlag("--dry-run", "Print command instead of running.");
            radar.addFlag("--json", "Emit machine-readable JSON.");
            radar.addRemainder("extra");

            Sub catalog = addSub("catalog", "Regenerate or check EVAL_CATALOG.md.");
            catalog.addFlag("--check", "Check mode (drift detection).");
            catalog.addFlag("--dry-run", "Print command instead of running.");
            catalog.addFlag("--json", "Emit machine-readable JSON.");

            Sub modelGroups = addSub("model-groups", "Regenerate or check MODEL_GROUPS.md.");
            modelGroups.addFlag("--check", "Check mode (drift detection).");
            modelGroups.addFlag("--dry-run", "Print command instead of running.");
            modelGroups.addFlag("--json", "Emit machine-readable JSON.");

            Sub list = addSub("list", "Discover categories / tiers / models / evals.");
            // `--json` is declared on the `list` parent so the parser
            // accepts it regardless of which child subcommand follows.
            // (The Python argparse version inherits parent flags to
            // child subcommands; this small re-declaration mirrors that.)
            list.addFlag("--json", null);
            list.addSub("categories", "List eval categories.").addFlag("--json", null);
            list.addSub("tiers", "List eval tiers.").addFlag("--json", null);
            Sub listModels = list.addSub("models", "List eval-tagged models.");
            listModels.addValue("--group", null, "Filter by group (e.g. set0).");
            listModels.addValue("--provider", null, "Filter by provider prefix.");
            listModels.addFlag("--json", null);
            Sub listEvals = list.addSub("evals", "List discovered eval functions.");
            listEvals.addValue("--category", null, "Filter by category.");
            listEvals.addFlag("--json", null);
        }

        private void addSharedRunOptions(Sub sub) {
            sub.addValue("--model", null, "Model identifier.");
            sub.addList("--eval-category", "Restrict to one eval category (repeatable).");
            sub.addList("--eval-category-exclude", "Exclude one eval category (repeatable).");
            sub.addList("--eval-tier", "Restrict to one eval tier (repeatable).");
            sub.addValue("--openai-reasoning-effort", null, "OpenAI reasoning effort.");
            sub.addValue("--openrouter-provider", null, "OpenRouter provider.");
            sub.addFlag("--openrouter-allow-fallbacks", "Allow OpenRouter fallbacks.");
            sub.addValue("--repl", null, "REPL mode.");
            sub.addFlag("--dry-run", "Print the command instead of running.");
            sub.addFlag("--json", "Emit machine-readable JSON.");
        }

        private Sub addSub(String name, String help) {
            Sub s = new Sub(name, help);
            subcommands.add(s);
            return s;
        }

        /** Last exit code produced by {@link #error(String)} or {@link #parse(String[], PrintStream)}. */
        public int lastExitCode() {
            return exitCode;
        }

        /** Print an error message and remember the exit code (mirrors {@code argparse.error}). */
        public void error(String message) {
            err.println(message);
            exitCode = EXIT_CONFIG;
        }

        /** Parse the argv; returns empty on error. */
        public Optional<Parsed> parse(String[] argv, PrintStream out) {
            this.err = (out == null) ? System.err
                    : new PrintStream(System.err, true, StandardCharsets.UTF_8);
            int idx = 0;
            if (argv.length == 0) {
                error("usage: deepagents-evals <subcommand> [options]");
                return Optional.empty();
            }
            String command = argv[idx++];
            Sub sub = subcommands.stream()
                    .filter(s -> s.name.equals(command))
                    .findFirst()
                    .orElse(null);
            if (sub == null) {
                error("error: unknown command: " + command);
                return Optional.empty();
            }
            Parsed parsed = new Parsed();
            parsed.command = command;
            List<String> positionalList = new ArrayList<>();
            // The active sub: starts as the top-level sub, may descend into
            // a nested sub when the first positional matches a known child
            // (e.g. `list models --group X` -> active sub becomes `list.models`).
            Sub active = sub;
            // The declared remainder key (if any) for the *active* sub's
            // declaration. Only flips on after we see the explicit `--`
            // sentinel mid-parse (Python argparse semantics), not on
            // declaration (R-bugfix-1).
            String remainderKey = null;
            boolean inRemainder = false;
            for (Option opt : active.options) {
                if (opt.remainder) {
                    remainderKey = opt.name;
                    break;
                }
            }
            while (idx < argv.length) {
                String arg = argv[idx];
                if (inRemainder) {
                    switch (remainderKey == null ? "pytest_extra" : remainderKey) {
                        case "pytest_extra" -> parsed.pytestExtra.add(arg);
                        case "extra" -> parsed.extra.add(arg);
                        default -> parsed.pytestExtra.add(arg);
                    }
                    idx++;
                    continue;
                }
                // Explicit `--` toggles remainder mode (Python argparse semantics).
                if ("--".equals(arg)) {
                    inRemainder = true;
                    idx++;
                    continue;
                }
                if (arg.startsWith("--") || (arg.startsWith("-") && arg.length() == 2)) {
                    Option opt = active.findOption(arg);
                    if (opt == null) {
                        error("error: unknown option: " + arg);
                        return Optional.empty();
                    }
                    if (opt.flag) {
                        setFlag(parsed, opt);
                        idx++;
                    } else {
                        if (idx + 1 >= argv.length) {
                            error("error: option " + arg + " requires a value");
                            return Optional.empty();
                        }
                        String value = argv[idx + 1];
                        if (!setValue(parsed, opt, value)) {
                            error("error: invalid value for " + arg + ": " + value);
                            return Optional.empty();
                        }
                        idx += 2;
                    }
                } else {
                    // Positional: if the active sub has nested subs and this
                    // token matches one of them, descend into the nested sub
                    // (R-bugfix-2). The token is also added to positionalList
                    // so the dispatch layer can read it (e.g. listTarget for
                    // `list`).
                    Sub nested = active.findSub(arg);
                    if (nested != null && !active.subs.isEmpty() && positionalList.isEmpty()) {
                        active = nested;
                        // Inherit the parent's remainder key (if any) so the
                        // child can still consume trailing positional args.
                        remainderKey = null;
                        for (Option opt : active.options) {
                            if (opt.remainder) {
                                remainderKey = opt.name;
                                break;
                            }
                        }
                    }
                    positionalList.add(arg);
                    idx++;
                }
            }
            if ("list".equals(command)) {
                if (positionalList.isEmpty()) {
                    error("error: list requires a <target>");
                    return Optional.empty();
                }
                parsed.listTarget = positionalList.get(0);
            } else if ("aggregate".equals(command)) {
                if (positionalList.isEmpty()) {
                    error("error: aggregate requires a <directory>");
                    return Optional.empty();
                }
                parsed.aggregateDir = Path.of(positionalList.get(0));
            }
            return Optional.of(parsed);
        }

        private void setFlag(Parsed parsed, Option opt) {
            switch (opt.name) {
                case "--dry-run" -> parsed.dryRun = true;
                case "--json" -> parsed.json = true;
                case "--check" -> parsed.check = true;
                case "--openrouter-allow-fallbacks" -> parsed.openrouterAllowFallbacks = true;
                case "--toy" -> parsed.toy = true;
                default -> { /* unknown flag - ignore */ }
            }
        }

        private boolean setValue(Parsed parsed, Option opt, String value) {
            switch (opt.name) {
                case "--model" -> parsed.model = value;
                case "--report" -> parsed.report = Path.of(value);
                case "--eval-category" -> parsed.evalCategory.add(value);
                case "--eval-category-exclude" -> parsed.evalCategoryExclude.add(value);
                case "--eval-tier" -> {
                    if (!KNOWN_TIERS.contains(value)) {
                        return false;
                    }
                    parsed.evalTier.add(value);
                }
                case "--openai-reasoning-effort" -> parsed.openaiReasoningEffort = value;
                case "--openrouter-provider" -> parsed.openrouterProvider = value;
                case "--repl" -> parsed.repl = value;
                case "--out-dir" -> parsed.outDir = Path.of(value);
                case "--summary-out" -> parsed.summaryOut = Path.of(value);
                case "--retry-failed" -> parsed.retryFailed = Path.of(value);
                case "--summary" -> parsed.summary = Path.of(value);
                case "--results" -> parsed.results = Path.of(value);
                case "-o", "--output" -> parsed.output = Path.of(value);
                case "--trials" -> {
                    try {
                        parsed.trials = Integer.parseInt(value);
                    } catch (NumberFormatException ex) {
                        return false;
                    }
                }
                case "--group" -> parsed.group = value;
                case "--provider" -> parsed.provider = value;
                case "--category" -> parsed.category = value;
                default -> {
                    return false;
                }
            }
            return true;
        }
    }

    /* ----------------------------- schema ----------------------------- */

    private static final class Option {
        final String name;
        final String alias;
        final boolean flag;
        final boolean list;
        final boolean remainder;
        final String help;

        Option(String name, String alias, boolean flag, boolean list, boolean remainder, String help) {
            this.name = name;
            this.alias = alias;
            this.flag = flag;
            this.list = list;
            this.remainder = remainder;
            this.help = help;
        }
    }

    private static final class Sub {
        final String name;
        final String help;
        final List<Option> options = new ArrayList<>();
        final List<Sub> subs = new ArrayList<>();
        Sub parent;

        Sub(String name, String help) {
            this.name = name;
            this.help = help;
        }

        Sub addValue(String name, String alias, String help) {
            options.add(new Option(name, alias, false, false, false, help));
            return this;
        }

        Sub addFlag(String name, String help) {
            options.add(new Option(name, null, true, false, false, help));
            return this;
        }

        Sub addList(String name, String help) {
            options.add(new Option(name, null, false, true, false, help));
            return this;
        }

        Sub addRemainder(String name) {
            options.add(new Option(name, null, false, false, true, null));
            return this;
        }

        Sub addSub(String name, String help) {
            Sub s = new Sub(name, help);
            s.parent = this;
            subs.add(s);
            return s;
        }

        Option findOption(String token) {
            for (Option o : options) {
                if (o.name.equals(token) || (o.alias != null && o.alias.equals(token))) {
                    return o;
                }
            }
            return null;
        }

        Sub findSub(String token) {
            for (Sub s : subs) {
                if (s.name.equals(token)) {
                    return s;
                }
            }
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static List<String> asList(String... items) {
        return Arrays.asList(items);
    }
}
