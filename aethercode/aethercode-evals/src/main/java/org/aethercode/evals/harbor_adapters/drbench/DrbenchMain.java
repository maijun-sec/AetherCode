package org.aethercode.evals.harbor_adapters.drbench;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI driver for generating DRBench Harbor tasks by id.
 *
 * <p>Run as {@code java org.aethercode.evals.harbor_adapters.drbench.DrbenchMain}.
 * Java 21 port of {@code harbor_adapters.drbench.main}.</p>
 */
public final class DrbenchMain {

    private DrbenchMain() {}

    /**
     * Generate one or more DRBench Harbor tasks, or populate a generated dataset.
     *
     * @param argv command-line arguments, excluding the program name
     * @throws IllegalArgumentException on mutually exclusive flag combinations
     *                                  or when no task set is identified
     */
    public static void main(String[] argv) throws java.io.IOException {
        ParsedArgs args = parseArgs(argv == null ? new String[0] : argv);
        try {
            // Each maintenance mode is mutually exclusive of every other mode, so that a
            // read-only check can never be combined with a flag that writes.
            boolean[] exclusive = {
                    args.refreshDigests,
                    args.refreshLabels,
                    args.checkLabels,
                    args.checkSubsets,
                    args.populate != null,
                    !args.taskIds.isEmpty(),
                    args.limit != null,
                    args.all,
            };
            if (args.refreshDigests) {
                requireExclusive(exclusive, "refresh-digests", 0);
                int count = DrbenchAdapter.refreshImageDigests(null);
                System.out.println("Refreshed " + count + " DRBench image digest(s)");
                return;
            }
            if (args.refreshLabels) {
                requireExclusive(exclusive, "refresh-labels", 1);
                int count = DrbenchAdapter.refreshTaskLabels();
                System.out.println("Refreshed labels for " + count + " DRBench task(s)");
                return;
            }
            if (args.checkLabels) {
                requireExclusive(exclusive, "check-labels", 2);
                List<String> problems = DrbenchAdapter.verifyTaskLabels();
                if (!problems.isEmpty()) {
                    for (String problem : problems) {
                        System.out.println("vendor/task_labels.json: " + problem);
                    }
                    throw new IllegalArgumentException(
                            problems.size() + " DRBench label mismatch(es); refresh with `--refresh-labels`");
                }
                System.out.println("DRBench task labels match the pinned upstream configs");
                return;
            }
            if (args.checkSubsets) {
                requireExclusive(exclusive, "check-subsets", 3);
                List<String> problems = DrbenchAdapter.verifySubsets();
                if (!problems.isEmpty()) {
                    for (String problem : problems) {
                        System.out.println("vendor/subsets: " + problem);
                    }
                    throw new IllegalArgumentException(
                            problems.size() + " DRBench subset mismatch(es) against "
                                    + DrbenchAdapter.UPSTREAM_SHA);
                }
                System.out.println("DRBench subset lists match the pinned upstream commit");
                return;
            }
            if (args.populate != null) {
                if (!args.taskIds.isEmpty() || args.limit != null || args.all) {
                    throw new IllegalArgumentException(
                            "`--populate` is mutually exclusive with `--task-ids`/`--limit`/`--all`");
                }
                int count = DrbenchAdapter.populateCorpus(args.populate);
                System.out.println("Populated " + count + " DRBench task(s) in " + args.populate);
                return;
            }
            if (args.outputDir == null) {
                throw new IllegalArgumentException(
                        "`--output-dir` is required unless `--populate` is given");
            }
            List<String> taskIds = resolveTaskIds(args);
            for (String taskId : taskIds) {
                DrbenchAdapter.generateTask(args.outputDir, taskId);
            }
            System.out.println("Generated " + taskIds.size() + " DRBench task(s) in " + args.outputDir);
        } catch (RuntimeException ex) {
            System.err.println("error: " + ex.getMessage());
            System.exit(2);
        }
    }

    private static void requireExclusive(boolean[] exclusive, String flag, int idx) {
        long count = 0;
        for (boolean b : exclusive) {
            if (b) {
                count++;
            }
        }
        if (count > 1) {
            throw new IllegalArgumentException("`--" + flag + "` is mutually exclusive with the other modes");
        }
    }

    private static List<String> resolveTaskIds(ParsedArgs args) throws IOException {
        if (!args.taskIds.isEmpty()) {
            if (args.all || args.limit != null) {
                throw new IllegalArgumentException(
                        "`--task-ids` is mutually exclusive with `--all`/`--limit`");
            }
            return args.taskIds;
        }
        List<String> available = DrbenchAdapter.availableTaskIds();
        if (args.all) {
            if (args.limit != null) {
                throw new IllegalArgumentException("`--all` is mutually exclusive with `--limit`");
            }
            return available;
        }
        if (args.limit != null) {
            return available.subList(0, Math.min(args.limit, available.size()));
        }
        throw new IllegalArgumentException(
                "One of `--task-ids`, `--limit`, or `--all` must be provided");
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
                case "--refresh-digests" -> a.refreshDigests = true;
                case "--refresh-labels" -> a.refreshLabels = true;
                case "--check-labels" -> a.checkLabels = true;
                case "--check-subsets" -> a.checkSubsets = true;
                case "--limit" -> a.limit = Integer.parseInt(argv[++i]);
                case "--all" -> a.all = true;
                default -> throw new IllegalArgumentException("unknown option: " + arg);
            }
        }
        return a;
    }

    /** Lightweight record for parsed CLI flags. */
    public static final class ParsedArgs {
        public Path outputDir;
        public final List<String> taskIds = new java.util.ArrayList<>();
        public Path populate;
        public boolean refreshDigests;
        public boolean refreshLabels;
        public boolean checkLabels;
        public boolean checkSubsets;
        public Integer limit;
        public boolean all;
    }
}
