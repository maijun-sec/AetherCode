package org.aethercode.examples.llmwiki;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Init-specific workflow for the LLM wiki.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/llm-wiki/init.py}. Resolves the
 * internal source flag from the installed LangSmith CLI's help
 * text, ensures the hub repo exists with {@code source=internal},
 * runs {@code hub init} + {@code hub push}, and returns the hub
 * URL.</p>
 */
public final class LlmWikiInit {
    private LlmWikiInit() {}

    /** Resolve an init flag set that enforces internal repo source. */
    public static List<String> resolveInternalSourceFlag(LlmWikiModels.CliDeps deps) {
        String helpText = hubInitHelpText(deps);
        return resolveInternalSourceFlagFromHelp(helpText);
    }

    /** Resolve source=internal flags from `hub init --help` output. */
    public static List<String> resolveInternalSourceFlagFromHelp(String helpText) {
        String lower = helpText == null ? "" : helpText.toLowerCase();
        if (lower.contains("--repo-source") && lower.contains("internal")) {
            return List.of("--repo-source", "internal");
        }
        if (lower.contains("--source") && lower.contains("internal")) {
            return List.of("--source", "internal");
        }
        if (lower.contains("--internal")) return List.of("--internal");
        return List.of();
    }

    private static String hubInitHelpText(LlmWikiModels.CliDeps deps) {
        LlmWikiModels.ProcessResult result = deps.runLangSmithCli().apply(List.of("hub", "init", "--help"));
        return ((result.stdout() == null ? "" : result.stdout())
                + "\n" + (result.stderr() == null ? "" : result.stderr())).toLowerCase();
    }

    /** Resolve an init description flag when supported. */
    public static List<String> resolveDescriptionFlag(String helpText, String description) {
        if (description == null) return List.of();
        String lower = helpText == null ? "" : helpText.toLowerCase();
        if (lower.contains("--description")) return List.of("--description", description);
        if (lower.contains("--desc")) return List.of("--desc", description);
        return List.of();
    }

    /** Extract repo source metadata from hub get payload. */
    public static Optional<String> extractRepoSource(Map<String, Object> payload) {
        Object direct = payload.get("source");
        if (direct instanceof String s) return Optional.of(s);
        Object repoSource = payload.get("repo_source");
        if (repoSource instanceof String s) return Optional.of(s);
        for (String key : List.of("repo", "data", "repository")) {
            Object nested = payload.get(key);
            if (nested instanceof Map<?, ?> n) {
                Object ns = n.get("source");
                if (ns instanceof String s) return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** Build the repos API path for owner/repo lookup. */
    public static String repoApiPath(Optional<String> owner, String repo) {
        String ownerSegment = owner.orElse("-");
        return "/api/v1/repos/" + URLEncoder.encode(ownerSegment, StandardCharsets.UTF_8)
                + "/" + URLEncoder.encode(repo, StandardCharsets.UTF_8);
    }

    /** Parse a hub identifier into owner/repo components. */
    public static OwnerRepo ownerRepoFromHubIdentifier(String hubIdentifier) {
        if (hubIdentifier == null || hubIdentifier.isEmpty()) {
            throw new LlmWikiHelpers.WikiError("Invalid hub identifier; missing repo handle.");
        }
        if (hubIdentifier.startsWith("-/")) {
            String repo = hubIdentifier.substring(2);
            if (repo.isEmpty()) {
                throw new LlmWikiHelpers.WikiError(
                        "Invalid hub identifier '" + hubIdentifier + "'; missing repo handle.");
            }
            return new OwnerRepo(Optional.empty(), repo);
        }
        int slash = hubIdentifier.indexOf('/');
        if (slash < 0) {
            throw new LlmWikiHelpers.WikiError(
                    "Invalid hub identifier '" + hubIdentifier + "'; expected OWNER/REPO or -/REPO.");
        }
        String owner = hubIdentifier.substring(0, slash);
        String repo = hubIdentifier.substring(slash + 1);
        if (owner.isEmpty() || repo.isEmpty()) {
            throw new LlmWikiHelpers.WikiError(
                    "Invalid hub identifier '" + hubIdentifier + "'; expected OWNER/REPO or -/REPO.");
        }
        return new OwnerRepo(Optional.of(owner), repo);
    }

    /** (owner, repo) tuple. */
    public record OwnerRepo(Optional<String> owner, String repo) {}

    /** Ensure the hub repo exists with source=internal before first push. */
    public static void ensureInternalRepoDefault(LlmWikiModels.RunnerConfig config, LlmWikiModels.CliDeps deps) {
        String repoPath = repoApiPath(config.owner(), config.repo());
        LlmWikiModels.ProcessResult result;
        try {
            result = deps.runLangSmithCli().apply(List.of("api", repoPath, "--format", "json"));
        } catch (LlmWikiHelpers.WikiError exc) {
            if (exc.getMessage() == null || !exc.getMessage().contains("404")) throw exc;
            Map<String, Object> create = new LinkedHashMap<>();
            create.put("repo_handle", config.repo());
            create.put("repo_type", "agent");
            create.put("is_public", false);
            create.put("source", "internal");
            String createBody = LlmWikiHelpers.toJson(create);
            try {
                deps.runLangSmithCli().apply(List.of(
                        "api", "/api/v1/repos", "-X", "POST",
                        "--body", createBody, "--format", "json"));
            } catch (LlmWikiHelpers.WikiError createExc) {
                if (createExc.getMessage() != null && createExc.getMessage().contains("409")) return;
                throw createExc;
            }
            return;
        }
        Object payload = LlmWikiHelpers.parseStdoutJson(result);
        if (!(payload instanceof Map)) {
            throw new LlmWikiHelpers.WikiError("Unable to verify repo source from repos API response.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) payload;
        Optional<String> source = extractRepoSource(map);
        if (source.isEmpty()) {
            throw new LlmWikiHelpers.WikiError(
                    "Wiki repo exists but has no source metadata. "
                            + "Delete and recreate it with `source=internal`.");
        }
        if (!"internal".equalsIgnoreCase(source.get())) {
            throw new LlmWikiHelpers.WikiError(
                    "Wiki repos must use `source=internal`. "
                            + "Found source=" + source.get() + " for "
                            + LlmWikiHelpers.hubCliRepoArg(LlmWikiHelpers.hubIdentifier(config.owner(), config.repo()))
                            + ".");
        }
    }

    /** Verify that the target hub repo source is internal. */
    public static void verifyInternalRepoSource(String hubIdentifier, LlmWikiModels.CliDeps deps) {
        LlmWikiModels.ProcessResult result = deps.runLangSmithCli().apply(List.of(
                "hub", "get", LlmWikiHelpers.hubCliRepoArg(hubIdentifier), "--format", "json"));
        Object payload = LlmWikiHelpers.parseStdoutJson(result);
        Optional<String> source = payload instanceof Map
                ? extractRepoSource((Map<String, Object>) payload)
                : Optional.empty();
        if (source.isEmpty()) {
            OwnerRepo ownerRepo = ownerRepoFromHubIdentifier(hubIdentifier);
            LlmWikiModels.ProcessResult apiResult = deps.runLangSmithCli().apply(List.of(
                    "api", repoApiPath(ownerRepo.owner(), ownerRepo.repo()), "--format", "json"));
            Object apiPayload = LlmWikiHelpers.parseStdoutJson(apiResult);
            if (apiPayload instanceof Map) {
                source = extractRepoSource((Map<String, Object>) apiPayload);
            }
            if (source.isEmpty()) {
                throw new LlmWikiHelpers.WikiError(
                        "Unable to verify repo source from `hub get --format json` output.");
            }
        }
        if (!"internal".equalsIgnoreCase(source.get())) {
            throw new LlmWikiHelpers.WikiError(
                    "Wiki repos must use `source=internal`. "
                            + "Found source=" + source.get() + " for "
                            + LlmWikiHelpers.hubCliRepoArg(hubIdentifier) + ".");
        }
    }

    /** Initialize a local topic repo and push its first hub revision. */
    public static LlmWikiModels.RunResult runInit(LlmWikiModels.RunnerConfig config, LlmWikiModels.CliDeps deps) {
        try {
            java.nio.file.Files.createDirectories(config.topicDir());
        } catch (java.io.IOException exc) {
            throw new LlmWikiHelpers.WikiError("cannot create " + config.topicDir());
        }
        LlmWikiHelpers.ensureNoSymlinks(config.topicDir());
        LlmWikiHelpers.ensureScaffold(config.topicDir(), config.topic(), false);

        String hubIdentifier = LlmWikiHelpers.hubIdentifier(config.owner(), config.repo());
        ensureInternalRepoDefault(config, deps);
        String helpText = hubInitHelpText(deps);
        List<String> sourceFlags = resolveInternalSourceFlagFromHelp(helpText);
        List<String> descriptionFlags = resolveDescriptionFlag(helpText, config.description().orElse(null));
        java.util.List<String> initArgs = new java.util.ArrayList<>(List.of(
                "hub", "init", "--type", "agent",
                "--dir", config.topicDir().toString(),
                "--name", config.repo(),
                "--force"));
        initArgs.addAll(sourceFlags);
        initArgs.addAll(descriptionFlags);
        deps.runLangSmithCli().apply(initArgs);
        LlmWikiHelpers.ensureScaffold(config.topicDir(), config.topic(), false);
        LlmWikiHelpers.validateTextOnlyDirectory(config.topicDir());
        deps.runLangSmithCli().apply(List.of(
                "hub", "push", LlmWikiHelpers.hubCliRepoArg(hubIdentifier),
                "--type", "agent", "--dir", config.topicDir().toString()));
        verifyInternalRepoSource(hubIdentifier, deps);
        return new LlmWikiModels.RunResult(Optional.empty(), Optional.of(
                LlmWikiHelpers.resolveHubUrl(config.owner(), config.repo())));
    }
}
