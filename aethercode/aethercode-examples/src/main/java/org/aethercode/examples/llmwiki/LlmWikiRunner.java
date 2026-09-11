package org.aethercode.examples.llmwiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CLI entrypoint for the LLM wiki example.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/llm-wiki/runner.py}. Parses CLI
 * args into a {@link LlmWikiModels.RunnerConfig}, then delegates
 * to {@link LlmWikiHelpers#run} which dispatches on the mode.</p>
 */
public final class LlmWikiRunner {
    private LlmWikiRunner() {}

    /** Build the CLI argument parser. Mirrors the Python port's argparse setup. */
    public static MiniArgParserLike buildParser() {
        MiniArgParserLike parser = new MiniArgParserLike();
        parser.addRequired("--mode", List.of("init", "ingest", "query", "lint"));
        parser.addRequired("--repo");
        parser.addOptional("--owner");
        parser.addOptional("--topic-dir");
        parser.addRepeated("--source");
        parser.addOptional("--note");
        parser.addOptional("--question");
        parser.addOptional("--model");
        parser.addOptional("--description");
        parser.addFlag("--review");
        return parser;
    }

    /** Parse CLI arguments into a runner config. */
    public static LlmWikiModels.RunnerConfig parseConfig(List<String> argv) {
        MiniArgParserLike.Parsed parsed = buildParser().parse(argv);
        LlmWikiModels.Mode mode = LlmWikiModels.Mode.valueOf(parsed.required("--mode").toUpperCase());
        if (mode == LlmWikiModels.Mode.INGEST && !parsed.has("--source")) {
            throw new LlmWikiHelpers.WikiError("--source is required in ingest mode");
        }
        if (mode == LlmWikiModels.Mode.QUERY && !parsed.has("--question")) {
            throw new LlmWikiHelpers.WikiError("--question is required in query mode");
        }
        String repo = normalizeRepo(parsed.required("--repo"), parsed.optional("--owner"));
        Optional<String> owner = normalizeOwner(parsed.optional("--owner"), repo);
        repo = stripOwner(repo);
        String topic = LlmWikiHelpers.defaultTopicFromRepo(repo);
        Path topicDir = LlmWikiHelpers.topicDirFor(topic, parsed.optional("--topic-dir"));
        List<Path> sources = parsed.repeated("--source").stream()
                .map(s -> Path.of(s).toAbsolutePath())
                .toList();
        return new LlmWikiModels.RunnerConfig(
                mode,
                topic,
                repo,
                owner,
                topicDir,
                sources,
                Optional.ofNullable(parsed.optional("--note")),
                Optional.ofNullable(parsed.optional("--question")),
                Optional.ofNullable(parsed.optional("--model")),
                Optional.ofNullable(parsed.optional("--description")),
                parsed.flag("--review"));
    }

    private static String normalizeRepo(String repo, String owner) {
        if (repo == null || repo.isBlank()) {
            throw new LlmWikiHelpers.WikiError("--repo must be non-empty");
        }
        return repo.strip();
    }

    private static Optional<String> normalizeOwner(String owner, String repo) {
        if (owner == null || owner.isBlank()) return Optional.empty();
        return Optional.of(owner.strip());
    }

    private static String stripOwner(String repo) {
        if (repo.contains("/")) {
            int slash = repo.indexOf('/');
            String maybeOwner = repo.substring(0, slash);
            String maybeRepo = repo.substring(slash + 1);
            if (maybeOwner.isEmpty() || maybeRepo.isEmpty() || maybeRepo.contains("/")) {
                throw new LlmWikiHelpers.WikiError("--repo must be REPO or OWNER/REPO");
            }
            return maybeRepo;
        }
        return repo;
    }

    /** Run the LLM wiki CLI. */
    public static int main(List<String> argv) {
        LlmWikiModels.RunnerConfig config;
        LlmWikiModels.RunResult result;
        try {
            config = parseConfig(argv);
            result = LlmWikiHelpers.run(config, LlmWikiHelpers.defaultDeps());
        } catch (LlmWikiHelpers.WikiError exc) {
            System.err.println("error: " + exc.getMessage());
            return 1;
        }
        result.answer().ifPresent(System.out::println);
        result.hubUrl().ifPresent(h -> System.out.println("Context Hub: " + h));
        return 0;
    }

    /** Convenience main entry point. */
    public static void main(String[] args) {
        System.exit(main(List.of(args)));
    }

    // -----------------------------------------------------------------
    // Mini CLI parser (different from the better-harness one to keep
    // the llm-wiki package independent).
    // -----------------------------------------------------------------

    /** A small CLI parser that supports required, optional, repeated, and flag options. */
    public static final class MiniArgParserLike {
        private final Map<String, String> required = new LinkedHashMap<>();
        private final Map<String, String> optional = new LinkedHashMap<>();
        private final Map<String, List<String>> repeated = new LinkedHashMap<>();
        private final java.util.Set<String> flags = new java.util.LinkedHashSet<>();

        public void addRequired(String name, List<String> choices) {
            // The choices are validated in {@link #parse}.
            required.put(name, String.join(",", choices));
        }
        public void addRequired(String name) { required.put(name, ""); }
        public void addOptional(String name) { optional.put(name, null); }
        public void addRepeated(String name) { repeated.put(name, List.of()); }
        public void addFlag(String name) { flags.add(name); }

        public Parsed parse(List<String> argv) {
            Map<String, String> requiredValues = new LinkedHashMap<>();
            Map<String, String> optionalValues = new LinkedHashMap<>(optional);
            Map<String, List<String>> repeatedValues = new LinkedHashMap<>();
            for (String k : repeated.keySet()) repeatedValues.put(k, new java.util.ArrayList<>());
            java.util.Set<String> flagValues = new java.util.LinkedHashSet<>();
            java.util.List<String> positional = new java.util.ArrayList<>();
            for (int i = 0; i < argv.size(); i++) {
                String a = argv.get(i);
                if (a.startsWith("--")) {
                    if (flags.contains(a)) { flagValues.add(a); continue; }
                    int eq = a.indexOf('=');
                    String name = eq >= 0 ? a.substring(0, eq) : a;
                    String value = eq >= 0 ? a.substring(eq + 1)
                            : (i + 1 < argv.size() ? argv.get(++i) : null);
                    if (required.containsKey(name)) requiredValues.put(name, value);
                    else if (optional.containsKey(name)) optionalValues.put(name, value);
                    else if (repeated.containsKey(name)) repeatedValues.get(name).add(value);
                } else {
                    positional.add(a);
                }
            }
            for (Map.Entry<String, String> e : required.entrySet()) {
                if (!requiredValues.containsKey(e.getKey())) {
                    throw new LlmWikiHelpers.WikiError("missing required arg " + e.getKey());
                }
                String choices = e.getValue();
                if (!choices.isEmpty()) {
                    String v = requiredValues.get(e.getKey());
                    if (!List.of(choices.split(",")).contains(v)) {
                        throw new LlmWikiHelpers.WikiError(
                                "invalid value for " + e.getKey() + ": " + v + " (expected one of " + choices + ")");
                    }
                }
            }
            return new Parsed(requiredValues, optionalValues, repeatedValues, flagValues, positional);
        }

        /** Parsed CLI invocation. */
        public record Parsed(
                Map<String, String> required,
                Map<String, String> optional,
                Map<String, List<String>> repeated,
                java.util.Set<String> flags,
                java.util.List<String> positional) {
            public String required(String name) { return required.get(name); }
            public String optional(String name) { return optional.get(name); }
            public List<String> repeated(String name) { return repeated.get(name); }
            public boolean flag(String name) { return flags.contains(name); }
            public boolean has(String name) {
                return required.containsKey(name) || optional.containsKey(name) || repeated.containsKey(name);
            }
        }
    }

    // Marker field so the package stays referenced even when the
    // primary entry point is invoked via {@code main}.
    @SuppressWarnings("unused")
    private static final class Marker {
        Path reference;
    }

    static Map<String, String> debugCli() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("parser", "MiniArgParserLike");
        try {
            Files.createTempDirectory("llm-wiki-debug-");
        } catch (IOException ignore) { /* best-effort */ }
        return out;
    }
}
