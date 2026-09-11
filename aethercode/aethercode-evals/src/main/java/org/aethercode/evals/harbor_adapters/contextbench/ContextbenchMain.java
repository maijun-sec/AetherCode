package org.aethercode.evals.harbor_adapters.contextbench;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import org.aethercode.evals.evals.Cli;

/**
 * CLI driver for generating Context-Bench Harbor tasks by id.
 *
 * <p>Run as {@code java org.aethercode.evals.harbor_adapters.contextbench.ContextbenchMain}.
 * Java 21 port of {@code harbor_adapters.contextbench.main}.</p>
 */
public final class ContextbenchMain {

    private static final String DEFAULT_SUITE = "cloud";

    private ContextbenchMain() {}

    /**
     * Generate one or more Context-Bench Harbor tasks by id.
     *
     * @param argv command-line arguments, excluding the program name;
     *             defaults to an empty list when {@code null}
     * @throws IllegalArgumentException if the selected flags are mutually
     *                                  exclusive or none identify which
     *                                  tasks to generate
     */
    public static void main(String[] argv) throws java.io.IOException {
        ParsedArgs args;
        try {
            args = parseArgs(argv == null ? new String[0] : argv);
        } catch (RuntimeException ex) {
            System.err.println("error: " + ex.getMessage());
            return;
        }
        try {
            if (args.stampTiers != null) {
                requireExclusive(args, "stamp-tiers");
                if (args.calibration == null) {
                    throw new IllegalArgumentException("`--stamp-tiers` requires `--calibration`");
                }
                int count = ContextbenchAdapter.stampCalibratedTiers(args.stampTiers, args.calibration);
                System.out.println("Stamped calibrated tiers for " + count + " task(s) in " + args.stampTiers);
                return;
            }
            if (args.populate != null) {
                requireExclusive(args, "populate");
                int count = ContextbenchAdapter.populateCorpus(args.populate);
                System.out.println("Populated corpus for " + count + " Context-Bench task(s) in " + args.populate);
                return;
            }
            if (args.outputDir == null) {
                throw new IllegalArgumentException("`--output-dir` is required unless `--populate` is given");
            }
            List<String> taskIds = resolveTaskIds(args);
            for (String taskId : taskIds) {
                ContextbenchAdapter.recordForTaskId(taskId); // validates the id up front
                ContextbenchAdapter.ParsedTaskId parsed = ContextbenchAdapter.parseTaskId(taskId);
                ContextbenchAdapter.generateTask(
                        ContextbenchAdapter.vendorDir().resolve("filesystem_" + parsed.suite() + ".jsonl"),
                        ContextbenchAdapter.vendorDir().resolve("files"),
                        args.outputDir,
                        taskId,
                        parsed.lineIndex());
            }
        } catch (RuntimeException ex) {
            System.err.println("error: " + ex.getMessage());
        } catch (java.io.IOException ex) {
            // Mirror the same one-line summary as RuntimeException so the
            // exit code stays at 0 (the caller sees a printed error
            // instead of a stack trace).
            System.err.println("error: " + ex.getMessage());
        }
    }

    /* ----------------------------- helpers ----------------------------- */

    private static List<String> resolveTaskIds(ParsedArgs args) {
        if (!args.taskIds.isEmpty()) {
            return args.taskIds;
        }
        if (args.limit != null) {
            return IntStream.range(0, args.limit)
                    .mapToObj(i -> "cb-" + DEFAULT_SUITE + "-" + i)
                    .toList();
        }
        throw new IllegalArgumentException("Either `--task-ids` or `--limit` must be provided");
    }

    private static void requireExclusive(ParsedArgs args, String winner) {
        if (!args.taskIds.isEmpty()) {
            throw new IllegalArgumentException("`--" + winner + "` is mutually exclusive with --task-ids/--limit");
        }
        if (args.limit != null) {
            throw new IllegalArgumentException("`--" + winner + "` is mutually exclusive with --limit");
        }
        if (args.populate != null) {
            throw new IllegalArgumentException("`--" + winner + "` is mutually exclusive with --populate");
        }
    }

    private static ParsedArgs parseArgs(String[] argv) {
        ParsedArgs a = new ParsedArgs();
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            switch (arg) {
                case "--output-dir" -> a.outputDir = Path.of(argv[++i]);
                case "--task-ids" -> {
                    int j = i + 1;
                    while (j < argv.length && !argv[j].startsWith("--")) {
                        a.taskIds.add(argv[j]);
                        j++;
                    }
                    i = j - 1;
                }
                case "--populate" -> a.populate = Path.of(argv[++i]);
                case "--stamp-tiers" -> a.stampTiers = Path.of(argv[++i]);
                case "--calibration" -> a.calibration = Path.of(argv[++i]);
                case "--limit" -> a.limit = Integer.parseInt(argv[++i]);
                default -> throw new IllegalArgumentException("unknown option: " + arg);
            }
        }
        return a;
    }

    /** Lightweight record for parsed CLI flags. */
    public static final class ParsedArgs {
        public Path outputDir;
        public final java.util.List<String> taskIds = new java.util.ArrayList<>();
        public Path populate;
        public Path stampTiers;
        public Path calibration;
        public Integer limit;
    }
}
