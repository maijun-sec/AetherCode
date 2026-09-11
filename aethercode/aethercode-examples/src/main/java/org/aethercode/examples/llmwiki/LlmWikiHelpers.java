package org.aethercode.examples.llmwiki;

import org.aethercode.examples.support.MiniJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Helper utilities for the LLM wiki example.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/llm-wiki/helpers.py}. Mirrors
 * the slugify, hub-identifier, scaffold, safe-write, and
 * review/apply-mode plumbing. The actual agent invocation is
 * pluggable through {@link LlmWikiModels.CliDeps}; the bundled
 * defaults stub out the LangSmith CLI binary (the Java port does
 * not depend on it).</p>
 */
public final class LlmWikiHelpers {
    private LlmWikiHelpers() {}

    /** Allowed source file extensions. */
    public static final Set<String> ALLOWED_TEXT_SUFFIXES = Set.of(
            ".md", ".txt", ".json", ".yaml", ".yml", ".csv");
    /** Default snapshot name. */
    public static final String DEFAULT_SNAPSHOT_NAME = "deepagents-wiki";
    /** Default docker image. */
    public static final String DEFAULT_DOCKER_IMAGE = "python:3";
    /** Default FS capacity (16 GiB). */
    public static final long DEFAULT_FS_CAPACITY = 16L * 1024 * 1024 * 1024;
    /** LangSmith binary names. */
    public static final List<String> LANGSMITH_BINARY_CANDIDATES = List.of("langsmith");
    /** Default base system prompt for the wiki agent. */
    public static final String BASE_SYSTEM_PROMPT = """
            You are an expert research synthesizer building a long-lived topic knowledge base.

            Mission:
            - Build an accurate, high-signal, source-grounded topic corpus in `/wiki/`.
            - Treat `/raw/` as immutable evidence inputs.
            - Convert raw notes into canonical, reusable understanding.

            Reasoning style:
            - Read primary source material before writing.
            - Distinguish facts from inferences.
            - Prefer compression-by-structure over compression-by-omission.
            - Keep uncertainty explicit.
            - Resolve contradictions when possible; otherwise record both claims and state what is unresolved.

            Writing and organization rules:
            - Maintain canonical pages per concept/entity/theme rather than many overlapping fragments.
            - Keep pages scannable with clear headings.
            - Include concise "What changed" summaries in your responses for runner-managed logging.
            - Keep `/wiki/index.md` authoritative for navigation.
            - Use recent `/log.md` entries as operational recency context before major synthesis.

            Evidence rules:
            - Every non-trivial claim should be traceable to the ingested source set.
            - Avoid introducing unsupported external facts.
            - If evidence is weak or missing, say so directly.

            Filesystem policy:
            - Never write to `/raw/`.
            - Never edit `/log.md`; the runner maintains append-only interaction entries.
            - Write only under `/wiki/`.
            """;

    /** Raised when the LLM wiki cannot complete a requested operation. */
    public static class WikiError extends RuntimeException {
        public WikiError(String message) { super(message); }
    }

    /** Convert a topic label into a stable slug. */
    public static String slugifyTopic(String topic) {
        StringBuilder slug = new StringBuilder();
        boolean lastDash = false;
        for (char c : topic.strip().toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                slug.append(c);
                lastDash = false;
                continue;
            }
            if (!lastDash) {
                slug.append('-');
                lastDash = true;
            }
        }
        String result = slug.toString().strip().replaceAll("^-+|-+$", "");
        return result.isEmpty() ? "topic" : result;
    }

    /** Resolve the local wiki directory path. */
    public static Path topicDirFor(String topic, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit).toAbsolutePath();
        }
        return Path.of("wikis", slugifyTopic(topic)).toAbsolutePath();
    }

    /** Create a display topic from a repo name. */
    public static String defaultTopicFromRepo(String repo) {
        String display = repo.replace('-', ' ').replace('_', ' ').strip();
        if (display.isEmpty()) return repo;
        StringBuilder out = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : display.toCharArray()) {
            if (Character.isWhitespace(c)) {
                capitalizeNext = true;
                out.append(c);
            } else {
                out.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            }
        }
        return out.toString();
    }

    /** Build a canonical hub identifier string. */
    public static String hubIdentifier(Optional<String> owner, String repo) {
        return owner.map(o -> o + "/" + repo).orElse("-/" + repo);
    }

    /** Normalize hub id values for cobra-based CLI parsing. */
    public static String hubCliRepoArg(String hubIdentifier) {
        if (hubIdentifier.startsWith("-/")) return hubIdentifier.substring(2);
        return hubIdentifier;
    }

    /** Compute the LangSmith app base URL from endpoint environment variables. */
    public static String appBaseUrl() {
        String endpoint = System.getenv().getOrDefault("LANGSMITH_ENDPOINT", "https://api.smith.langchain.com");
        URI parsed = URI.create(endpoint);
        String scheme = parsed.getScheme();
        if (scheme == null || scheme.isEmpty()) scheme = "https";
        String host = parsed.getHost();
        if (host == null || host.isEmpty()) host = parsed.getRawSchemeSpecificPart();
        if (host == null) host = "smith.langchain.com";
        if (host.startsWith("api.")) host = host.substring(4);
        return scheme + "://" + host;
    }

    /** Resolve a browser URL for the hub repo. */
    public static String resolveHubUrl(Optional<String> owner, String repo) {
        String base = appBaseUrl();
        if (owner.isPresent()) return base + "/hub/" + owner.get() + "/" + repo;
        return base + "/hub/" + repo;
    }

    /** Yield all paths rooted under a workspace directory. */
    public static Stream<Path> iterTreePaths(Path rootDir) {
        List<Path> out = new ArrayList<>();
        out.add(rootDir);
        try (Stream<Path> stream = Files.walk(rootDir)) {
            stream.sorted().forEach(out::add);
        } catch (IOException ignore) {
            // best-effort
        }
        return out.stream();
    }

    /** Reject workspace trees that contain symlinks. */
    public static void ensureNoSymlinks(Path rootDir) {
        try (Stream<Path> stream = Files.walk(rootDir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                if (Files.isSymbolicLink(path)) {
                    Path relative;
                    try { relative = rootDir.relativize(path); }
                    catch (Exception exc) { relative = path; }
                    throw new WikiError("Symlinks are not supported in wiki workspaces for security reasons: " + relative);
                }
            });
        } catch (IOException exc) {
            throw new WikiError("cannot walk " + rootDir);
        }
    }

    /** Write UTF-8 text while refusing symlink targets. */
    public static void safeWrite(Path path, String content) {
        safeWrite(path, content, false);
    }

    /** Write UTF-8 text while refusing symlink targets, optionally appending. */
    public static void safeWrite(Path path, String content, boolean append) {
        if (Files.isSymbolicLink(path)) {
            throw new WikiError("Refusing to write to symlink path: " + path);
        }
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            if (append) {
                Files.writeString(path, content,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, content,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException exc) {
            throw new WikiError("cannot write " + path + ": " + exc.getMessage());
        }
    }

    /** Write file content only when the target does not already exist. */
    public static void writeIfMissing(Path path, String content) {
        if (Files.isSymbolicLink(path)) {
            throw new WikiError("Refusing to write to symlink path: " + path);
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        safeWrite(path, content);
    }

    /** Build default AGENTS.md guidance content. */
    public static String agentsMd(String topic) {
        return "# " + topic + " Wiki\n\n"
                + "Use this file as the wiki schema/config for agent behavior.\n"
                + "Keep it concise and co-evolve it as the wiki and workflow change.\n\n"
                + "Rules:\n"
                + "- Treat `/raw/` as read-only source material.\n"
                + "- Ingest flow should be supervised: review takeaways first, then apply updates.\n"
                + "- Ingest updates should prioritize canonical concept/entity/theme pages.\n"
                + "- Prefer a flat `/wiki/` layout by default; create subdirectories only when they clearly improve organization.\n"
                + "- Use `/log.md` as recency context and keep it append-only.\n"
                + "- Do not edit `/log.md` directly; the runner appends structured timeline entries.\n"
                + "- Keep `/wiki/index.md` current as a content catalog.\n";
    }

    /** Ensure required topic workspace files and directories exist. */
    public static void ensureScaffold(Path topicDir, String topic, boolean overwriteAgents) {
        try {
            Files.createDirectories(topicDir.resolve("raw"));
            Files.createDirectories(topicDir.resolve("wiki"));
        } catch (IOException exc) {
            throw new WikiError("cannot scaffold " + topicDir);
        }
        writeIfMissing(topicDir.resolve("wiki").resolve("index.md"),
                LlmWikiIndex.emptyIndexText(topic));
        writeIfMissing(topicDir.resolve("log.md"), "# Change Log\n");
        Path agentsPath = topicDir.resolve("AGENTS.md");
        if (overwriteAgents || !Files.exists(agentsPath, LinkOption.NOFOLLOW_LINKS)) {
            safeWrite(agentsPath, agentsMd(topic));
        }
    }

    /** Validate that all files in a directory are UTF-8 text with allowed suffixes. */
    public static void validateTextOnlyDirectory(Path rootDir) {
        ensureNoSymlinks(rootDir);
        try (Stream<Path> stream = Files.walk(rootDir)) {
            stream.forEach(p -> {
                if (!Files.isRegularFile(p)) return;
                String name = p.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String suffix = dot >= 0 ? name.substring(dot).toLowerCase() : "";
                if (!ALLOWED_TEXT_SUFFIXES.contains(suffix)) {
                    Path rel = rootDir.relativize(p);
                    throw new WikiError("Unsupported file for v1 text-only hub pushes: " + rel
                            + ". Allowed extensions: md, txt, json, yaml, yml, csv.");
                }
                try {
                    Files.readString(p);
                } catch (IOException exc) {
                    Path rel = rootDir.relativize(p);
                    throw new WikiError("File " + rel + " is not valid UTF-8 text. "
                            + "Binary uploads are not supported in v1.");
                }
            });
        } catch (IOException exc) {
            throw new WikiError("cannot walk " + rootDir);
        }
    }

    /** Copy and de-duplicate source files into the workspace raw directory. */
    public static List<Path> stageSources(List<Path> sources, Path workspaceDir) {
        List<Path> staged = new ArrayList<>();
        Path rawDir = workspaceDir.resolve("raw");
        try { Files.createDirectories(rawDir); }
        catch (IOException exc) { throw new WikiError("cannot create " + rawDir); }
        for (Path source : sources) {
            if (!Files.isRegularFile(source)) {
                throw new WikiError("Source file not found: " + source);
            }
            String name = source.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String suffix = dot >= 0 ? name.substring(dot) : "";
            String stem = dot >= 0 ? name.substring(0, dot) : name;
            if (!ALLOWED_TEXT_SUFFIXES.contains(suffix.toLowerCase())) {
                throw new WikiError("Unsupported source file type for " + source
                        + ". Use text files with extensions: md, txt, json, yaml, yml, csv.");
            }
            String text;
            try { text = Files.readString(source); }
            catch (IOException exc) { throw new WikiError("Source file must be UTF-8 text: " + source); }
            Path destination = rawDir.resolve(source.getFileName());
            int counter = 2;
            while (Files.exists(destination) || Files.isSymbolicLink(destination)) {
                destination = rawDir.resolve(stem + "-" + counter + suffix);
                counter++;
            }
            safeWrite(destination, text);
            staged.add(destination);
        }
        return staged;
    }

    /** Extract textual content from agent message payloads. */
    public static String extractText(Object content) {
        if (content instanceof String s) return s;
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && "text".equals(m.get("type"))) {
                    Object t = m.get("text");
                    if (t instanceof String s) sb.append(s).append("\n");
                }
            }
            return sb.toString().stripTrailing();
        }
        return content == null ? "" : content.toString();
    }

    /** Return the final assistant text message from an agent invoke result. */
    public static String extractFinalAiMessage(Map<String, Object> result) {
        if (result == null) return "";
        Object messages = result.get("messages");
        if (!(messages instanceof List<?> list)) return "";
        for (int i = list.size() - 1; i >= 0; i--) {
            Object message = list.get(i);
            String msgType = null;
            if (message instanceof Map<?, ?> m) {
                msgType = (String) m.get("type");
            }
            if (!"ai".equals(msgType) && !"assistant".equals(msgType)) continue;
            Object content = message instanceof Map<?, ?> m ? m.get("content") : null;
            String text = extractText(content).strip();
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    /** Default LangSmith CLI runner that fails with a WikiError. */
    public static LlmWikiModels.ProcessResult defaultLangSmithCli(List<String> args) {
        throw new WikiError("LangSmith CLI invocation is not available in the Java port. "
                + "Wire a real client through CliDeps.runLangSmithCli.");
    }

    /** Default agent mode runner that fails with a WikiError. */
    public static String defaultAgentMode(Path workspaceDir, String topic, String prompt, String model) {
        throw new WikiError("Agent mode is not available in the Java port. "
                + "Wire a real client through CliDeps.runAgentMode.");
    }

    /** Default agent review mode runner that fails with a WikiError. */
    public static String defaultAgentReviewMode(Path workspaceDir, String topic, String prompt, String model) {
        return defaultAgentMode(workspaceDir, topic, prompt, model);
    }

    /** Default ask-user that reads from stdin. */
    public static String defaultAskUser(String prompt) {
        System.out.print(prompt);
        try {
            byte[] buf = new byte[1024];
            int n = System.in.read(buf);
            if (n < 0) throw new WikiError("Ingest review requires an interactive confirmation response.");
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw new WikiError("cannot read user input: " + exc.getMessage());
        }
    }

    /** Build default {@link LlmWikiModels.CliDeps}. */
    public static LlmWikiModels.CliDeps defaultDeps() {
        return new LlmWikiModels.CliDeps(
                LlmWikiHelpers::defaultLangSmithCli,
                LlmWikiHelpers::defaultAgentMode,
                LlmWikiHelpers::defaultAgentReviewMode,
                LlmWikiHelpers::defaultAskUser,
                () -> {
                    final Path dir;
                    try {
                        dir = Files.createTempDirectory("llm-wiki-");
                    } catch (IOException exc) {
                        throw new UncheckedIOException(exc);
                    }
                    return new LlmWikiModels.TempDir() {
                        @Override public String path() { return dir.toString(); }
                        @Override public void close() {
                            try (Stream<Path> stream = Files.walk(dir)) {
                                stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignore) {} });
                            } catch (IOException ignore) { }
                        }
                    };
                });
    }

    /** Parse JSON stdout from a process result. */
    public static Object parseStdoutJson(LlmWikiModels.ProcessResult result) {
        String stdout = result.stdout() == null ? "" : result.stdout().strip();
        if (stdout.isEmpty()) return null;
        try { return MiniJson.parse(stdout); }
        catch (Exception exc) { return null; }
    }

    /** Convenience: serialize a map / object to JSON via {@link MiniJson}. */
    public static String toJson(Object value) {
        return MiniJson.toJson(value);
    }

    /** Sanity check the slugify helper is symmetric. */
    public static Map<String, String> debugSummary() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("slug", slugifyTopic("Foo Bar Baz"));
        out.put("hub_id", hubIdentifier(Optional.empty(), "demo"));
        out.put("base_url", appBaseUrl());
        return out;
    }

    /**
     * Refresh the wiki index for the current workspace. Mirrors
     * the Python port's {@code _refresh_index}.
     */
    public static void refreshIndex(String topic, Path workspaceDir) {
        LlmWikiIndex.refreshIndex(topic, workspaceDir, LlmWikiHelpers::safeWrite);
    }

    /**
     * Append one log entry to the wiki log. Mirrors the Python
     * port's {@code _append_log_entry}.
     */
    public static void appendLogEntry(Path workspaceDir, String phase, String outcome,
                                      Map<String, Object> metadata, String summary) {
        LlmWikiLog.appendLogEntry(workspaceDir, phase, outcome, metadata, summary,
                LlmWikiHelpers::writeIfMissing,
                (path, content) -> LlmWikiHelpers.safeWrite(path, content));
    }

    /**
     * Run the requested wiki workflow. Mirrors the Python port's
     * {@code run} entry point.
     */
    public static LlmWikiModels.RunResult run(LlmWikiModels.RunnerConfig config,
                                              LlmWikiModels.CliDeps deps) {
        if (config.mode() == LlmWikiModels.Mode.INIT) {
            return LlmWikiInit.runInit(config, deps);
        }
        return runPullMode(config, deps);
    }

    /** Pull a hub repo, run the selected mode, and push updates. */
    public static LlmWikiModels.RunResult runPullMode(LlmWikiModels.RunnerConfig config,
                                                       LlmWikiModels.CliDeps deps) {
        String hubIdentifier = hubIdentifier(config.owner(), config.repo());
        LlmWikiModels.TempDir temp = deps.tempDirFactory().get();
        try {
            Path workspaceDir = Path.of(temp.path());
            deps.runLangSmithCli().apply(List.of(
                    "hub", "pull", hubCliRepoArg(hubIdentifier), "--dir", workspaceDir.toString()));
            ensureNoSymlinks(workspaceDir);
            ensureScaffold(workspaceDir, config.topic(), false);
            String answer;
            boolean shouldPush;
            String filedPath = null;
            if (config.mode() == LlmWikiModels.Mode.INGEST) {
                LlmWikiIngest.IngestResult result = LlmWikiIngest.runIngestWorkspace(config, workspaceDir, deps);
                answer = result.answer();
                shouldPush = result.shouldPush();
            } else if (config.mode() == LlmWikiModels.Mode.QUERY) {
                LlmWikiQuery.QueryResult result = LlmWikiQuery.runQueryWorkspace(config, workspaceDir, deps);
                answer = result.answer();
                shouldPush = result.shouldPush();
                filedPath = result.filedPath().orElse(null);
            } else {
                answer = LlmWikiLint.runLintWorkspace(config, workspaceDir, deps);
                shouldPush = true;
            }
            if (shouldPush) {
                validateTextOnlyDirectory(workspaceDir);
                deps.runLangSmithCli().apply(List.of(
                        "hub", "push", hubCliRepoArg(hubIdentifier),
                        "--type", "agent", "--dir", workspaceDir.toString()));
            }
            return new LlmWikiModels.RunResult(
                    Optional.ofNullable(answer),
                    Optional.of(resolveHubUrl(config.owner(), config.repo())));
        } finally {
            temp.close();
        }
    }

    // Re-export StandardOpenOption so the package can stay self-contained.
    static final class StandardOpenOption {
        static final java.nio.file.OpenOption CREATE = java.nio.file.StandardOpenOption.CREATE;
        static final java.nio.file.OpenOption APPEND = java.nio.file.StandardOpenOption.APPEND;
        static final java.nio.file.OpenOption TRUNCATE_EXISTING = java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
    }
}
