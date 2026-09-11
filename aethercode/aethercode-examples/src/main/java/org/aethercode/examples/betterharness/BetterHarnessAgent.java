package org.aethercode.examples.betterharness;

import org.aethercode.examples.support.MiniJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Outer-loop Deep Agent and proposer workspace helpers.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/better-harness/better_harness/agent.py}.
 * Builds the proposer workspace, invokes the outer Deep Agent
 * (mirroring the Python port's {@code run_non_interactive} call),
 * and serializes the resulting proposal + candidate variant.</p>
 *
 * <p>The actual deep-agent subprocess is not launched at port time
 * (the Java port does not bundle a Python runtime); the
 * {@link ProposerInvoker} hook is the pluggable entry point for a
 * real run.</p>
 */
public final class BetterHarnessAgent {
    private BetterHarnessAgent() {}

    /** Default system prompt the Python port uses. */
    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are Better Agent, an outer-loop Deep Agent that improves another agent harness.

            Your job is to read eval feedback and edit the provided harness surface files so the next eval run passes more cases.

            Rules:
            - Edit only files under /current.
            - Do not edit train_cases, history, or bookkeeping files except /proposal.md.
            - Prefer general harness fixes over case-specific hacks.
            - Do not overfit to the visible examples. Infer the broader policy or behavior they expose, then encode that as general instructions, tools, skills, or middleware changes.
            - The files under /current are the actual harness surfaces. Edit them as final prompt text, code, or config that the target agent should load during eval.
            - If a surface is a code file such as a tool or middleware file, write the real code or registration needed there, not notes or pseudocode.
            - If you change tool or middleware behavior, update both the implementation and any registration or wiring surfaces you were given.
            - Use surface_manifest.json and task.md to understand how each editable file maps back to the target harness.
            - Use the visible failures and the train case files to decide what to change.
            - Keep changes concise and coherent.
            - Make the smallest set of edits needed for the visible train failures in this iteration.
            - Stop as soon as /current and /proposal.md are updated.
            - When done, write a short explanation to /proposal.md.""";

    /** Hook for invoking the outer Deep Agent. */
    @FunctionalInterface
    public interface ProposerInvoker {
        /** Run the outer agent against one proposer workspace; return the final assistant message. */
        String invoke(BetterHarnessCore.Experiment experiment, ProposerWorkspace workspace);
    }

    /** Default no-op invoker: just returns an empty string. */
    public static ProposerInvoker noopInvoker() {
        return (experiment, workspace) -> "";
    }

    /** Materialized workspace for the outer Deep Agent. */
    public record ProposerWorkspace(
            Path root,
            Path currentDir,
            Path proposalFile,
            Map<String, Path> surfaceFiles) {}

    /** (proposal, candidate) tuple returned by {@link #proposeVariant}. */
    public record ProposalPair(BetterHarnessCore.Proposal proposal, BetterHarnessCore.Variant candidate) {}

    private static final ProposerInvoker DEFAULT_INVOKER = noopInvoker();
    private static volatile ProposerInvoker ACTIVE_INVOKER = DEFAULT_INVOKER;

    /** Replace the active invoker (used by tests / plug-in runtimes). */
    public static void setActiveInvoker(ProposerInvoker invoker) {
        ACTIVE_INVOKER = invoker == null ? DEFAULT_INVOKER : invoker;
    }

    /**
     * Build one proposer workspace for the current iteration.
     * Mirrors the Python port's {@code build_proposer_workspace}.
     */
    public static ProposerWorkspace buildProposerWorkspace(
            BetterHarnessCore.Experiment experiment,
            BetterHarnessCore.Variant current,
            BetterHarnessCore.SplitResult trainResult,
            BetterHarnessCore.RunLayout layout,
            int iteration) {
        Path root = layout.proposerWorkspaceDir(iteration);
        try {
            if (Files.exists(root)) {
                deleteRecursively(root);
            }
            Files.createDirectories(root.resolve("current"));
        } catch (IOException exc) {
            throw new RuntimeException("cannot prepare proposer workspace " + root, exc);
        }
        Map<String, Path> surfaceFiles = new LinkedHashMap<>();
        Map<String, Object> manifest = new LinkedHashMap<>();
        for (Map.Entry<String, BetterHarnessCore.Surface> e : experiment.surfaces().entrySet()) {
            String name = e.getKey();
            BetterHarnessCore.Surface s = e.getValue();
            Path target = root.resolve("current").resolve(s.filename());
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, current.values().get(name));
            } catch (IOException exc) {
                throw new RuntimeException("cannot write surface " + target, exc);
            }
            surfaceFiles.put(name, target);
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("kind", s.kind());
            sm.put("target", s.target());
            sm.put("file", root.relativize(target).toString().replace('\\', '/'));
            manifest.put(name, sm);
        }
        try {
            Files.writeString(root.resolve("surface_manifest.json"),
                    MiniJson.toJson(manifest));
            writeTrainArtifacts(experiment, trainResult, root);
            writeVisibleHistory(layout, root);
            copyPriorVisibleArtifacts(layout, root, iteration);
            writeTaskFile(experiment, current, trainResult, root);
            Path proposalFile = root.resolve("proposal.md");
            Files.writeString(proposalFile, """
                    # Proposal

                    - Summary:
                    - Why this should help:
                    - Surfaces changed:
                    """);
        } catch (IOException exc) {
            throw new RuntimeException("cannot write proposer artifacts", exc);
        }
        return new ProposerWorkspace(root, root.resolve("current"), root.resolve("proposal.md"), surfaceFiles);
    }

    /** Load surface values back out of one proposer workspace. */
    public static Map<String, String> loadCandidateValues(
            BetterHarnessCore.Variant current, ProposerWorkspace workspace) {
        Map<String, String> values = new LinkedHashMap<>(current.values());
        for (Map.Entry<String, Path> e : workspace.surfaceFiles().entrySet()) {
            try {
                values.put(e.getKey(), Files.readString(e.getValue()).strip());
            } catch (IOException exc) {
                throw new RuntimeException("cannot read surface " + e.getValue(), exc);
            }
        }
        return values;
    }

    /** Read the proposer summary if present. */
    public static String readProposalSummary(ProposerWorkspace workspace) {
        if (!Files.exists(workspace.proposalFile())) return "";
        try {
            return Files.readString(workspace.proposalFile()).strip();
        } catch (IOException exc) {
            return "";
        }
    }

    /**
     * Run the outer Deep Agent once and return its candidate
     * variant. Mirrors the Python port's {@code propose_variant}.
     */
    public static ProposalPair proposeVariant(
            BetterHarnessCore.Experiment experiment,
            BetterHarnessCore.Variant current,
            BetterHarnessCore.SplitResult trainResult,
            BetterHarnessCore.RunLayout layout,
            int iteration) {
        ProposerWorkspace workspace = buildProposerWorkspace(experiment, current, trainResult, layout, iteration);
        String finalMessage = ACTIVE_INVOKER.invoke(experiment, workspace);
        Map<String, String> values = loadCandidateValues(current, workspace);
        List<String> changed = new ArrayList<>();
        for (String name : experiment.surfaces().keySet()) {
            if (!values.get(name).equals(current.values().get(name))) {
                changed.add(name);
            }
        }
        java.util.Collections.sort(changed);
        String summary = readProposalSummary(workspace);
        BetterHarnessCore.Proposal proposal = new BetterHarnessCore.Proposal(
                changed, workspace.root().toString(), summary, Optional.ofNullable(finalMessage));
        BetterHarnessCore.Variant candidate = BetterHarnessPatching.buildVariant(
                experiment, "iter-" + String.format("%03d", iteration), values);
        try {
            Files.writeString(workspace.root().resolve("result.json"),
                    MiniJson.toJson(Map.of(
                            "proposal", proposal.toMap(),
                            "candidate_variant", candidate.toMap())));
        } catch (IOException exc) {
            // best-effort
        }
        return new ProposalPair(proposal, candidate);
    }

    private static void writeTrainArtifacts(BetterHarnessCore.Experiment experiment,
                                            BetterHarnessCore.SplitResult trainResult,
                                            Path root) throws IOException {
        List<Map<String, Object>> failures = new ArrayList<>();
        for (var outcome : trainResult.failingOutcomes()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("case_id", outcome.caseId());
            entry.put("stratum", outcome.stratum());
            entry.put("status", outcome.status());
            outcome.failureMessage().ifPresentOrElse(
                    v -> entry.put("failure_message", v),
                    () -> entry.put("failure_message", outcome.status()));
            failures.add(entry);
        }
        Files.writeString(root.resolve("train_failures.json"), MiniJson.toJson(Map.of("failures", failures)));
        Files.writeString(root.resolve("train_summary.json"), MiniJson.toJson(trainResult.toMap()));
        Path trainCasesDir = root.resolve("train_cases");
        Files.createDirectories(trainCasesDir);
        if ("pytest".equals(experiment.runner())) {
            // Java port does not copy source files; emit a manifest
            // of rendered case ids so the proposer still has a
            // listing to reason about.
            List<String> rendered = experiment.renderedCaseIds("train");
            Files.writeString(trainCasesDir.resolve("manifest.txt"),
                    String.join("\n", rendered));
        }
    }

    private static void writeVisibleHistory(BetterHarnessCore.RunLayout layout, Path root) throws IOException {
        Path historyDir = root.resolve("history");
        Files.createDirectories(historyDir);
        List<String> summaries = new ArrayList<>();
        if (Files.exists(layout.visibleIterationsDir())) {
            try (Stream<Path> stream = Files.list(layout.visibleIterationsDir())) {
                List<Path> decisions = stream.filter(p -> p.getFileName().toString().equals("decision.json"))
                        .sorted().toList();
                for (Path p : decisions) {
                    Map<String, Object> payload = MiniJson.parseObject(Files.readString(p));
                    summaries.add("- Iteration " + payload.get("iteration")
                            + ": " + payload.get("decision")
                            + " (train " + payload.get("train_passed")
                            + "/" + payload.get("train_total") + ")");
                }
            }
        }
        if (summaries.isEmpty()) summaries.add("- No previous iterations yet.");
        Files.writeString(historyDir.resolve("visible_history.md"),
                "# Visible History\n\n" + String.join("\n", summaries) + "\n");
    }

    private static void copyPriorVisibleArtifacts(BetterHarnessCore.RunLayout layout,
                                                  Path root, int iteration) throws IOException {
        Path priorRoot = root.resolve("history").resolve("prior_visible");
        Files.createDirectories(priorRoot);
        Path trainRoot = layout.visibleRoot().resolve("train");
        if (Files.exists(trainRoot)) {
            copyDir(trainRoot, priorRoot.resolve("train"));
        }
        Path iterationsRoot = priorRoot.resolve("iterations");
        Files.createDirectories(iterationsRoot);
        if (Files.exists(layout.visibleIterationsDir())) {
            try (Stream<Path> stream = Files.list(layout.visibleIterationsDir())) {
                stream.sorted().forEach(decisionPath -> {
                    try {
                        if (decisionPath.getFileName().toString().equals("decision.json")) {
                            if (decisionPath.getParent().getFileName().toString()
                                    .equals(String.format("%03d", iteration))) {
                                return;
                            }
                            Path target = iterationsRoot.resolve(decisionPath.getParent().getFileName().toString());
                            Files.createDirectories(target);
                            Files.copy(decisionPath, target.resolve("decision.json"));
                        }
                    } catch (IOException exc) {
                        // best-effort
                    }
                });
            }
        }
    }

    private static void writeTaskFile(BetterHarnessCore.Experiment experiment,
                                      BetterHarnessCore.Variant current,
                                      BetterHarnessCore.SplitResult trainResult,
                                      Path root) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Better Agent Task\n\n");
        sb.append("You are improving another agent harness using eval feedback.\n\n");
        sb.append("Rules:\n");
        sb.append("- Edit only files under `current/`.\n");
        sb.append("- Do not edit files under `train_cases/`, `history/`, or this task file.\n");
        sb.append("- Prefer general harness improvements over task-specific hacks.\n");
        sb.append("- Treat files under `current/` as the real harness surfaces. Write final prompt text, code, or config there.\n");
        sb.append("- Use `surface_manifest.json` to understand how each editable file maps back to the target harness.\n");
        sb.append("- Use the visible train failures and train case files to decide what to change.\n");
        sb.append("- Keep changes concise and coherent.\n");
        sb.append("- When you finish, update `proposal.md` with a short summary.\n\n");
        sb.append("Current variant: `").append(current.key()).append("`\n");
        sb.append("Current train score: `").append(trainResult.passed())
                .append("/").append(trainResult.total()).append("`\n\n");
        sb.append("Editable surfaces:\n");
        for (Map.Entry<String, BetterHarnessCore.Surface> e : experiment.surfaces().entrySet()) {
            sb.append("- `").append(e.getKey()).append("` -> `current/")
                    .append(e.getValue().filename()).append("` (")
                    .append(e.getValue().kind()).append(", target `")
                    .append(e.getValue().target()).append("`)\n");
        }
        sb.append("\nVisible train failures:\n");
        for (var outcome : trainResult.failingOutcomes()) {
            sb.append("- `").append(outcome.caseId()).append("` [")
                    .append(outcome.stratum()).append("]: ");
            sb.append(outcome.failureMessage().orElse(outcome.status())).append("\n");
        }
        if (trainResult.failingOutcomes().isEmpty()) {
            sb.append("- No train failures are currently visible.\n");
        }
        Files.writeString(root.resolve("task.md"), sb.toString());
    }

    /** Compose the proposer system prompt. Mirrors the Python port's helper. */
    public static String composeSystemPrompt(BetterHarnessCore.Experiment experiment) {
        if (experiment.betterAgentSystemPrompt() != null) {
            return experiment.betterAgentSystemPrompt().strip() + "\n\n" + DEFAULT_SYSTEM_PROMPT;
        }
        return DEFAULT_SYSTEM_PROMPT;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.delete(p); }
                        catch (IOException ignore) { }
                    });
        }
    }

    private static void copyDir(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        try (var stream = Files.walk(src)) {
            stream.forEach(p -> {
                try {
                    Path target = dst.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(target);
                    else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target);
                    }
                } catch (IOException ignore) { }
            });
        }
    }
}
