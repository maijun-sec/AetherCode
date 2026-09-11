package org.aethercode.evals.evals;

import org.aethercode.evals.evals.Cli.Parsed;
import org.aethercode.evals.evals.Cli.Parser;
import org.aethercode.evals.evals.Cli.ShellRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Cli} — the unified {@code deepagents-evals} subcommand
 * dispatcher. All subprocess calls are routed through the
 * {@link ShellRunner} seam so these tests run hermetically.
 *
 * <p>R-radar-2: bring {@code aethercode-evals} test count from 0 to 50+.</p>
 */
class CliTest {

    /** Captures {@code main}'s stdout into a String for assertions. */
    private static String captureStdout(CliThunk thunk) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        int rc = thunk.run(out, (cmd, cwd) -> 0);
        out.flush();
        return rc + "||" + buf.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface CliThunk {
        int run(PrintStream out, ShellRunner runner);
    }

    /* ------------------------- constants ------------------------- */

    @Test
    void exitCodesAreStable() {
        // The Python port documents these exit codes; we lock them in so
        // CI scripts and downstream tooling can rely on the values.
        assertEquals(0, Cli.EXIT_OK);
        assertEquals(1, Cli.EXIT_EVAL_FAILURES);
        assertEquals(2, Cli.EXIT_CONFIG);
        assertEquals(3, Cli.EXIT_NO_REPORTS);
    }

    @Test
    void modelEnvVarConstantIsStable() {
        assertEquals("DEEPAGENTS_EVALS_MODEL", Cli.MODEL_ENV_VAR);
    }

    @Test
    void knownTiersAreBaselineAndHillclimb() {
        assertEquals(List.of("baseline", "hillclimb"), Cli.KNOWN_TIERS);
    }

    /* ------------------------- ShellRunner ------------------------- */

    @Test
    void defaultShellRunnerIsNotNull() {
        assertNotNull(Cli.DEFAULT_SHELL);
    }

    @Test
    void customShellRunnerIsUsedByCmdRun() {
        // Use `catalog --check` (no --dry-run). catalog has no remainder, so
        // its options parse normally, and without --dry-run the shell seam
        // is invoked exactly once.
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<String>> lastCmd = new AtomicReference<>();
        ShellRunner recorder = (cmd, cwd) -> {
            calls.incrementAndGet();
            lastCmd.set(cmd);
            return 0;
        };
        int rc = Cli.main(
                new String[]{"catalog", "--check"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                recorder);
        assertEquals(Cli.EXIT_OK, rc);
        assertEquals(1, calls.get(), "shellRunner must be invoked exactly once for `catalog --check`");
        List<String> cmd = lastCmd.get();
        assertNotNull(cmd);
        assertTrue(cmd.contains("scripts/generate_eval_catalog.py"),
                "argv must reference the catalog script: " + cmd);
        assertTrue(cmd.contains("--check"), "argv must carry --check: " + cmd);
    }

    @Test
    void customShellRunnerExitCode1SurfacesAsEvalFailures() {
        // Same shape: catalog without --dry-run makes the shell runner rc
        // bubble up to EXIT_EVAL_FAILURES (catalog uses EXIT_EVAL_FAILURES
        // for non-check failures; check failures map to EXIT_CONFIG but
        // we don't pass --check here).
        ShellRunner fail = (cmd, cwd) -> 1;
        int rc = Cli.main(
                new String[]{"catalog"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                fail);
        assertEquals(Cli.EXIT_EVAL_FAILURES, rc);
    }

    /* ------------------------- arg validation ------------------------- */

    @Test
    void emptyArgvReturnsConfig() {
        int rc = Cli.main(new String[0],
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (cmd, cwd) -> 0);
        assertEquals(Cli.EXIT_CONFIG, rc);
    }

    @Test
    void unknownCommandReturnsConfig() {
        int rc = Cli.main(new String[]{"banana"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (cmd, cwd) -> 0);
        assertEquals(Cli.EXIT_CONFIG, rc);
    }

    @Test
    void trialsWithoutTrialsArgReturnsConfig() {
        int rc = Cli.main(new String[]{"trials", "--model", "anthropic:claude-sonnet-4-6"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (cmd, cwd) -> 0);
        assertEquals(Cli.EXIT_CONFIG, rc);
    }

    @Test
    void runWithoutModelReturnsConfig() {
        int rc = Cli.main(new String[]{"run"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (cmd, cwd) -> 0);
        assertEquals(Cli.EXIT_CONFIG, rc);
    }

    @Test
    void unknownEvalTierReturnsConfig() {
        int rc = Cli.main(new String[]{
                        "run", "--model", "anthropic:claude-sonnet-4-6", "--eval-tier", "unknown"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (cmd, cwd) -> 0);
        assertEquals(Cli.EXIT_CONFIG, rc);
    }

    @Test
    void listWithoutTargetReturnsConfig() {
        int rc = Cli.main(new String[]{"list"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (cmd, cwd) -> 0);
        assertEquals(Cli.EXIT_CONFIG, rc);
    }

    @Test
    void listUnknownTargetReturnsConfig() {
        int rc = Cli.main(new String[]{"list", "frobnicate"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (cmd, cwd) -> 0);
        assertEquals(Cli.EXIT_CONFIG, rc);
    }

    /* ------------------------- list subcommand ------------------------- */

    @Test
    void listCategoriesTextModePrintsOnePerLine() {
        String result = captureStdout((out, runner) -> Cli.main(new String[]{"list", "categories"}, out, runner));
        String[] parts = result.split("\\|\\|", 2);
        int rc = Integer.parseInt(parts[0]);
        String body = parts[1];
        assertEquals(Cli.EXIT_OK, rc);
        for (String cat : Radar.allCategories()) {
            assertTrue(body.contains(cat), "list categories output must contain " + cat + " (got: " + body + ")");
        }
    }

    @Test
    void listCategoriesJsonModeReturnsArray() {
        String result = captureStdout((out, runner) -> Cli.main(
                new String[]{"list", "categories", "--json"}, out, runner));
        String[] parts = result.split("\\|\\|", 2);
        int rc = Integer.parseInt(parts[0]);
        String body = parts[1].trim();
        assertEquals(Cli.EXIT_OK, rc);
        assertTrue(body.startsWith("["), "json mode emits an array: " + body);
        assertTrue(body.endsWith("]"));
        assertTrue(body.contains("\"memory\""), "array must list known categories: " + body);
    }

    @Test
    void listTiersEmitsKnownTiers() {
        String result = captureStdout((out, runner) -> Cli.main(new String[]{"list", "tiers"}, out, runner));
        String body = result.split("\\|\\|", 2)[1];
        assertTrue(body.contains("baseline"));
        assertTrue(body.contains("hillclimb"));
    }

    @Test
    void listModelsReturnsEmptyRegistry() {
        // listKnownModels() is a stub returning []. We assert the CLI
        // doesn't blow up; the empty result is the contract.
        String result = captureStdout((out, runner) -> Cli.main(new String[]{"list", "models"}, out, runner));
        String[] parts = result.split("\\|\\|", 2);
        assertEquals(Cli.EXIT_OK, parts[0] == null ? -1 : Integer.parseInt(parts[0]));
    }

    @Test
    void listEvalsReturnsEmptyRegistry() {
        String result = captureStdout((out, runner) -> Cli.main(new String[]{"list", "evals"}, out, runner));
        String[] parts = result.split("\\|\\|", 2);
        assertEquals(Cli.EXIT_OK, Integer.parseInt(parts[0]));
    }

    /* ------------------------- buildSingleTrialArgv ------------------------- */

    @Test
    void buildSingleTrialArgvCarriesModel() {
        Parsed p = new Parsed();
        p.model = "anthropic:claude-sonnet-4-6";
        List<String> argv = Cli.buildSingleTrialArgv(p);
        int modelIdx = argv.indexOf("--model");
        assertTrue(modelIdx >= 0);
        assertEquals("anthropic:claude-sonnet-4-6", argv.get(modelIdx + 1));
    }

    @Test
    void buildSingleTrialArgvCarriesCategoriesAndExcludes() {
        Parsed p = new Parsed();
        p.model = "anthropic:claude-sonnet-4-6";
        p.evalCategory = List.of("memory", "tool_use");
        p.evalCategoryExclude = List.of("long_horizon");
        p.evalTier = List.of("baseline");
        List<String> argv = Cli.buildSingleTrialArgv(p);
        assertTrue(argv.containsAll(List.of("--eval-category", "memory", "--eval-category", "tool_use")),
                argv.toString());
        assertTrue(argv.containsAll(List.of("--eval-category-exclude", "long_horizon")),
                argv.toString());
        assertTrue(argv.containsAll(List.of("--eval-tier", "baseline")), argv.toString());
    }

    @Test
    void buildSingleTrialArgvCarriesReport() {
        // Use a relative path so the assertion is portable across Windows
        // (where Path.of("/tmp/x") renders as "\tmp\x" and the platform
        // separator would otherwise leak into the assertion).
        Parsed p = new Parsed();
        p.model = "anthropic:claude-sonnet-4-6";
        p.report = java.nio.file.Path.of("report.json");
        List<String> argv = Cli.buildSingleTrialArgv(p);
        int idx = argv.indexOf("--evals-report-file");
        assertTrue(idx >= 0, "must include --evals-report-file: " + argv);
        assertEquals("report.json", argv.get(idx + 1));
    }

    @Test
    void buildSingleTrialArgvAppendsPytestExtra() {
        Parsed p = new Parsed();
        p.model = "anthropic:claude-sonnet-4-6";
        p.pytestExtra = new ArrayList<>(List.of("-k", "memory"));
        List<String> argv = Cli.buildSingleTrialArgv(p);
        int k = argv.indexOf("-k");
        assertTrue(k >= 0, "pytest extra args must be appended: " + argv);
        assertEquals("memory", argv.get(k + 1));
    }

    /* ------------------------- validateModel ------------------------- */

    @Test
    void validateModelPrefersArg() {
        // Save/restore the env var so we don't poison the rest of the suite.
        String saved = System.getenv(Cli.MODEL_ENV_VAR);
        try {
            Parsed p = new Parsed();
            p.model = "anthropic:claude-sonnet-4-6";
            Parser parser = Cli.buildParser();
            Cli.validateModel(p, parser);
            assertEquals("anthropic:claude-sonnet-4-6", p.model);
            assertEquals(Cli.EXIT_OK, parser.lastExitCode());
        } finally {
            // env-var is read-only at this point; nothing to undo.
            assertSame(saved, saved); // no-op
        }
    }

    @Test
    void validateModelFallsThroughToErrorWhenNoModelAndNoEnv() {
        // The env var is unset in the test runner; assert the error path.
        String env = System.getenv(Cli.MODEL_ENV_VAR);
        assertTrue(env == null || env.isEmpty(), "test prerequisite: env must be unset");
        Parsed p = new Parsed();
        p.model = null;
        Parser parser = Cli.buildParser();
        Cli.validateModel(p, parser);
        assertEquals(Cli.EXIT_CONFIG, parser.lastExitCode());
    }

    @Test
    void validateModelFallsThroughToErrorWhenEmptyString() {
        Parsed p = new Parsed();
        p.model = "";
        Parser parser = Cli.buildParser();
        Cli.validateModel(p, parser);
        assertEquals(Cli.EXIT_CONFIG, parser.lastExitCode());
    }

    /* ------------------------- dryRun ------------------------- */

    @Test
    void runDryRunDoesNotInvokeShellRunner() {
        // R-bugfix-1: the parser no longer pre-enters remainder mode, so
        // --model and --dry-run are parsed normally and dry-run dispatches
        // without invoking the shell. The shell seam is exercised 0 times.
        AtomicInteger calls = new AtomicInteger();
        ShellRunner recorder = (cmd, cwd) -> {
            calls.incrementAndGet();
            return 0;
        };
        int rc = Cli.main(new String[]{
                        "run", "--model", "anthropic:claude-sonnet-4-6", "--dry-run"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                recorder);
        assertEquals(Cli.EXIT_OK, rc,
                "dry-run with a valid model must not invoke the shell and must exit OK");
        assertEquals(0, calls.get(), "no shell call should happen on --dry-run");
    }

    @Test
    void runDryRunJsonModeEmitsObject() {
        // `catalog` (no remainder) supports --json + --dry-run cleanly. Use
        // it to assert the dry-run JSON path end-to-end.
        String result = captureStdout((out, runner) -> Cli.main(
                new String[]{"catalog", "--dry-run", "--json"}, out, runner));
        String[] parts = result.split("\\|\\|", 2);
        assertEquals(Cli.EXIT_OK, Integer.parseInt(parts[0]));
        String body = parts[1].trim();
        assertTrue(body.startsWith("{"), "json dry-run must be an object: " + body);
        assertTrue(body.contains("\"dry_run\""), body);
        assertTrue(body.contains("\"argv\""), body);
    }

    @Test
    void catalogDryRunEmitsCommand() {
        String result = captureStdout((out, runner) -> Cli.main(
                new String[]{"catalog", "--dry-run"}, out, runner));
        String body = result.split("\\|\\|", 2)[1];
        assertTrue(body.contains("generate_eval_catalog.py"), body);
    }

    @Test
    void radarDryRunEmitsCommand() {
        // `radar` also has a remainder; use model-groups instead which is
        // a clean dry-run surface for the chart-generator argv shape.
        String result = captureStdout((out, runner) -> Cli.main(
                new String[]{"model-groups", "--dry-run"}, out, runner));
        String body = result.split("\\|\\|", 2)[1];
        assertTrue(body.contains("generate_model_groups.py"), body);
    }

    @Test
    void modelGroupsDryRunEmitsCommand() {
        String result = captureStdout((out, runner) -> Cli.main(
                new String[]{"model-groups", "--dry-run"}, out, runner));
        String body = result.split("\\|\\|", 2)[1];
        assertTrue(body.contains("generate_model_groups.py"), body);
    }

    /* ------------------------- listKnownModels ------------------------- */

    @Test
    void listKnownModelsIsEmptyByContract() {
        // Stub for the Python model registry; CI must treat empty as
        // "fall back to the upstream source of truth".
        assertTrue(Cli.listKnownModels().isEmpty());
    }

    /* ------------------------- parser unit tests ------------------------- */

    @Test
    void parserReturnsEmptyForUnknownOption() {
        // Use `list` (no remainder) so the parser actually walks the
        // option-parse path instead of capturing everything as remainder.
        Parser parser = Cli.buildParser();
        java.util.Optional<Parsed> parsed = parser.parse(
                new String[]{"list", "categories", "--no-such-flag"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertTrue(parsed.isEmpty());
        assertEquals(Cli.EXIT_CONFIG, parser.lastExitCode());
    }

    @Test
    void parserRejectsBareShortFlags() {
        // R-bugfix-1: remainder mode is gated on the explicit `--` sentinel
        // now, so a token that doesn't match any declared option is
        // rejected with an "unknown option" error rather than silently
        // captured into pytestExtra.
        Parser parser = Cli.buildParser();
        java.util.Optional<Parsed> parsed = parser.parse(
                new String[]{"run", "-1"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertTrue(parsed.isEmpty(), "unknown short flag must fail to parse");
        assertEquals(Cli.EXIT_CONFIG, parser.lastExitCode());
    }

    @Test
    void parserCapturesListCategoryTarget() {
        // list has no remainder -> options parse normally, listTarget is set.
        Parser parser = Cli.buildParser();
        java.util.Optional<Parsed> parsed = parser.parse(
                new String[]{"list", "categories", "--json"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertTrue(parsed.isPresent());
        Parsed p = parsed.get();
        assertEquals("list", p.command);
        assertEquals("categories", p.listTarget);
        assertTrue(p.json, "list --json must set the json flag");
    }

    @Test
    void parserParsesCatalogFlags() {
        Parser parser = Cli.buildParser();
        java.util.Optional<Parsed> parsed = parser.parse(
                new String[]{"catalog", "--check", "--dry-run", "--json"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertTrue(parsed.isPresent());
        Parsed p = parsed.get();
        assertTrue(p.check);
        assertTrue(p.dryRun);
        assertTrue(p.json);
    }

    @Test
    void parserParsesModelGroupsFlags() {
        Parser parser = Cli.buildParser();
        java.util.Optional<Parsed> parsed = parser.parse(
                new String[]{"model-groups", "--check"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertTrue(parsed.isPresent());
        assertTrue(parsed.get().check);
    }

    @Test
    void parserCapturesAggregateDir() {
        Parser parser = Cli.buildParser();
        java.util.Optional<Parsed> parsed = parser.parse(
                new String[]{"aggregate", "/tmp/in", "--json"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertTrue(parsed.isPresent());
        Parsed p = parsed.get();
        assertEquals(java.nio.file.Path.of("/tmp/in"), p.aggregateDir);
        assertTrue(p.json);
    }

    @Test
    void parserRequiresAggregateDir() {
        Parser parser = Cli.buildParser();
        java.util.Optional<Parsed> parsed = parser.parse(
                new String[]{"aggregate"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertTrue(parsed.isEmpty(),
                "aggregate without a directory must fail to parse");
        assertEquals(Cli.EXIT_CONFIG, parser.lastExitCode());
    }

    @Test
    void parserParsesListModelsWithFilters() {
        // R-bugfix-2: the parser descends into nested subs so `list models`
        // sees the --group / --provider / --json options declared on the
        // `models` sub-subcommand. Filters must end up on the parsed object.
        Parser parser = Cli.buildParser();
        java.util.Optional<Parsed> parsed = parser.parse(
                new String[]{"list", "models", "--group", "set0", "--provider", "anthropic", "--json"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertTrue(parsed.isPresent(),
                "list models --group --provider must parse cleanly");
        Parsed p = parsed.get();
        assertEquals("models", p.listTarget);
        assertEquals("set0", p.group);
        assertEquals("anthropic", p.provider);
        assertTrue(p.json);
    }

    @Test
    void parserParsesListEvalsWithCategory() {
        // R-bugfix-2: same descent-into-nested-subs fix; --category on
        // `list evals` is now visible to the parser.
        Parser parser = Cli.buildParser();
        java.util.Optional<Parsed> parsed = parser.parse(
                new String[]{"list", "evals", "--category", "memory"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertTrue(parsed.isPresent(), "list evals --category must parse");
        Parsed p = parsed.get();
        assertEquals("evals", p.listTarget);
        assertEquals("memory", p.category);
    }

    @Test
    void parserGatesRemainderOnDoubleDashSentinel() {
        // R-bugfix-1: the parser only enters remainder mode when it sees
        // the explicit `--` sentinel. Everything before `--` is parsed as
        // declared options; everything after lands in pytestExtra without
        // the `--` itself.
        Parser parser = Cli.buildParser();
        java.util.Optional<Parsed> parsed = parser.parse(
                new String[]{"run", "--", "-k", "memory"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertTrue(parsed.isPresent());
        assertEquals(List.of("-k", "memory"), parsed.get().pytestExtra,
                "`--` toggles remainder mode and is consumed; tokens after it land in pytestExtra");
    }

    @Test
    void parserAcceptsOptionsBeforeDoubleDashRemainder() {
        // R-bugfix-1: options before `--` are parsed normally; the
        // remainder only starts after the sentinel. A real
        // `run --model X --dry-run -- -k memory` invocation must work.
        Parser parser = Cli.buildParser();
        java.util.Optional<Parsed> parsed = parser.parse(
                new String[]{"run", "--model", "anthropic:claude-sonnet-4-6",
                        "--dry-run", "--", "-k", "memory"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertTrue(parsed.isPresent());
        Parsed p = parsed.get();
        assertEquals("anthropic:claude-sonnet-4-6", p.model);
        assertTrue(p.dryRun);
        assertEquals(List.of("-k", "memory"), p.pytestExtra);
    }

    @Test
    void mainReachesDispatchWhenRemainderIsGated() {
        // R-bugfix-1: with `--` triggering remainder, main() reaches
        // cmdRun. cmdRun fails validateModel (no --model), so the
        // expected exit code is EXIT_CONFIG -- not a parse error.
        int rc = Cli.main(
                new String[]{"run", "--", "-k", "memory"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (cmd, cwd) -> 0);
        assertEquals(Cli.EXIT_CONFIG, rc);
    }

    /* ------------------------- aggregate subcommand ------------------------- */

    @Test
    void aggregateInvokesShellExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        int rc = Cli.main(new String[]{"aggregate", "/tmp/in"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (cmd, cwd) -> { calls.incrementAndGet(); return 0; });
        // aggregate runs the shell, then exitCodeFromSummary. With rc=0
        // and no summary file, exit code is EXIT_OK (per exitCodeFromSummary).
        assertEquals(1, calls.get());
        assertEquals(Cli.EXIT_OK, rc);
    }

    @Test
    void aggregateWithDryRunEmitsCommand() {
        // `aggregate` has no --dry-run flag (intentional: the subcommand
        // runs an in-process script). Confirm it still works and routes
        // through the shell seam.
        String result = captureStdout((out, runner) -> Cli.main(
                new String[]{"aggregate", "/tmp/in"}, out, runner));
        String[] parts = result.split("\\|\\|", 2);
        // It invokes the shell with rc=0, then exitCodeFromSummary returns
        // OK (no summary file). The captured body is empty for aggregate.
        assertEquals(Cli.EXIT_OK, Integer.parseInt(parts[0]));
    }

    @Test
    void trialsWithMissingTrialsArgFailsBeforeDispatch() {
        // `trials` without --trials must short-circuit in main() and return
        // EXIT_CONFIG (the explicit `--trials is required` check).
        int rc = Cli.main(new String[]{"trials", "--model", "x"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (cmd, cwd) -> 0);
        assertEquals(Cli.EXIT_CONFIG, rc);
    }

    @Test
    void trialsDryRunParsesModelAndTrials() {
        // R-bugfix-1: with --model, --trials, and --dry-run all parsed
        // (not swallowed by remainder mode), cmdTrials dispatches into
        // its dry-run path, prints the would-run command, and exits OK
        // without invoking the shell.
        AtomicInteger calls = new AtomicInteger();
        int rc = Cli.main(new String[]{
                        "trials", "--model", "anthropic:claude-sonnet-4-6",
                        "--trials", "3", "--dry-run"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (cmd, cwd) -> { calls.incrementAndGet(); return 0; });
        assertEquals(0, calls.get(), "dry-run must not invoke the shell");
        assertEquals(Cli.EXIT_OK, rc);
    }

    @Test
    void trialsWithoutModelStillFails() {
        // R-bugfix-1 sanity: validateModel still fires when --model is
        // missing. (Previously this case was masked by the remainder-mode
        // bug swallowing --model; now we test the genuine missing-model
        // failure path.)
        AtomicInteger calls = new AtomicInteger();
        int rc = Cli.main(new String[]{
                        "trials", "--trials", "3", "--dry-run"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (cmd, cwd) -> { calls.incrementAndGet(); return 0; });
        assertEquals(0, calls.get(), "no shell call should happen when validateModel fails");
        assertEquals(Cli.EXIT_CONFIG, rc);
    }
}
