package org.aethercode.examples.llmwiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Ingest-specific workflow for the LLM wiki.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/llm-wiki/ingest.py}. Provides
 * source expansion, ingest review / apply prompt builders, and the
 * {@link #runIngestWorkspace} entry point that mirrors the Python
 * port's review-then-apply flow.</p>
 */
public final class LlmWikiIngest {
    private LlmWikiIngest() {}

    /** Result from one ingest workspace pass. */
    public record IngestResult(String answer, boolean shouldPush) {}

    /** Expand source arguments into a deterministic list of file paths. */
    public static List<Path> expandSources(List<Path> sources) {
        List<Path> expanded = new ArrayList<>();
        java.util.LinkedHashSet<Path> seen = new java.util.LinkedHashSet<>();
        for (Path source : sources) {
            if (Files.isSymbolicLink(source)) {
                throw new LlmWikiHelpers.WikiError("Symlink sources are not supported for ingest: " + source);
            }
            if (!Files.exists(source)) {
                throw new LlmWikiHelpers.WikiError("Source path not found: " + source);
            }
            if (Files.isRegularFile(source)) {
                Path resolved = source.toAbsolutePath();
                if (seen.add(resolved)) expanded.add(resolved);
                continue;
            }
            if (Files.isDirectory(source)) {
                List<Path> dirFiles = collectDirectorySources(source);
                if (dirFiles.isEmpty()) {
                    throw new LlmWikiHelpers.WikiError("Source directory is empty: " + source);
                }
                for (Path p : dirFiles) if (seen.add(p)) expanded.add(p);
                continue;
            }
            throw new LlmWikiHelpers.WikiError("Unsupported source path type: " + source);
        }
        return expanded;
    }

    /** Collect allowed file paths from a source directory recursively. */
    public static List<Path> collectDirectorySources(Path directory) {
        List<Path> collected = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted().forEach(p -> {
                if (Files.isSymbolicLink(p)) {
                    throw new LlmWikiHelpers.WikiError("Symlink sources are not supported for ingest: " + p);
                }
                if (Files.isRegularFile(p)) collected.add(p.toAbsolutePath());
            });
        } catch (IOException exc) {
            throw new LlmWikiHelpers.WikiError("cannot walk " + directory);
        }
        return collected;
    }

    /** Build a compact source hint for structured log metadata. */
    public static String ingestSourceHint(List<Path> staged) {
        if (staged.isEmpty()) return "none";
        List<String> names = staged.stream().map(p -> p.getFileName().toString()).toList();
        if (names.size() <= 2) return String.join(", ", names);
        return names.get(0) + ", " + names.get(1) + ", +" + (names.size() - 2) + " more";
    }

    /** Build the ingest review prompt for staged source material. */
    public static String buildIngestReviewPrompt(String topic, List<Path> staged, String note) {
        String sourceBlock = staged.stream()
                .map(p -> "- /raw/" + p.getFileName())
                .reduce((a, b) -> a + "\n" + b).orElse("");
        String noteBlock = note == null ? "(none)" : note;
        return "Review the staged sources for topic '" + topic + "' and prepare a deep ingest plan.\n\n"
                + "Phase constraint: review-only. Do not create, edit, move, or delete files yet.\n\n"
                + "Analysis standards:\n"
                + "- Read every staged source before proposing wiki edits.\n"
                + "- Distinguish direct evidence from inference.\n"
                + "- Prefer canonical page updates over creating fragmented pages.\n"
                + "- Preserve uncertainty; do not invent unsupported claims.\n"
                + "- Use source filename citations for non-trivial claims.\n\n"
                + "Required output format (markdown):\n"
                + "## 1) Source-by-source extraction\n"
                + "- For each source: purpose, key claims, useful details, confidence/risk notes.\n\n"
                + "## 2) Proposed wiki change set\n"
                + "- Enumerate concrete file actions under `/wiki/`.\n"
                + "- For each file: create/update, why it changes, and core additions.\n"
                + "- Prefer canonical concept/entity/theme pages over per-source summary files.\n"
                + "- Prefer a flat `/wiki/` layout by default; create subdirectories only when they clearly improve organization at current scale.\n\n"
                + "## 3) Cross-source synthesis and structure\n"
                + "- Shared themes, entity relationships, timelines, and important backlinks.\n"
                + "- Mention candidate canonical pages if current structure is fragmented.\n\n"
                + "## 4) Contradictions and unresolved claims\n"
                + "- Conflicts between sources, what is unresolved, and how pages should reflect this.\n\n"
                + "## 5) Index updates and recency notes\n"
                + "- Exact updates needed for `/wiki/index.md`.\n"
                + "- Optional recency note candidates for runner-managed `/log.md` timeline summaries.\n\n"
                + "## 6) Gaps and follow-up questions\n"
                + "- Missing evidence, suggested next sources, and optional future page candidates.\n\n"
                + "Expect broad wiki impact where warranted; one source can update many pages.\n"
                + "Never write to `/raw/`.\n\n"
                + "Staged sources:\n" + sourceBlock + "\n\n"
                + "Operator note: " + noteBlock + "\n";
    }

    /** Build the ingest apply prompt. */
    public static String buildIngestApplyPrompt(String topic, List<Path> staged,
                                                 String reviewSummary, String note) {
        String sourceBlock = staged.stream()
                .map(p -> "- /raw/" + p.getFileName())
                .reduce((a, b) -> a + "\n" + b).orElse("");
        String noteBlock = note == null ? "(none)" : note;
        return "Apply an approved ingest update for topic '" + topic + "'.\n\n"
                + "Required workflow:\n"
                + "1) Read all staged files in `/raw/` before editing wiki content.\n"
                + "2) Update canonical concept/entity/theme pages with high-signal evidence.\n"
                + "3) Integrate cross-source synthesis, not just per-source summaries.\n"
                + "4) Mark contradictions explicitly and preserve unresolved uncertainty.\n"
                + "5) Update `/wiki/index.md`.\n"
                + "6) Do not edit `/log.md`; the runner appends structured timeline entries.\n"
                + "7) Never write to `/raw/`.\n"
                + "8) Prefer files directly under `/wiki/`; only create subdirectories when they are clearly needed for organization.\n\n"
                + "Writing standards:\n"
                + "- Keep pages scannable with clear headings and concise prose.\n"
                + "- Use source filename citations for non-trivial claims.\n"
                + "- Avoid duplicative pages; merge into canonical pages when possible.\n"
                + "- If evidence is weak or conflicting, state that directly.\n\n"
                + "Return a concise apply report after edits:\n"
                + "A) Files created\n"
                + "B) Files updated\n"
                + "C) Key synthesis changes\n"
                + "D) Remaining uncertainties and suggested next ingest targets\n\n"
                + "Approved review plan:\n" + reviewSummary + "\n\n"
                + "Staged sources:\n" + sourceBlock + "\n\n"
                + "Operator note: " + noteBlock + "\n";
    }

    /** Run ingest mode against a pulled workspace directory. */
    public static IngestResult runIngestWorkspace(
            LlmWikiModels.RunnerConfig config,
            Path workspaceDir,
            LlmWikiModels.CliDeps deps) {
        List<Path> expanded = expandSources(config.sources());
        List<Path> staged = LlmWikiHelpers.stageSources(expanded, workspaceDir);
        int sourceCount = staged.size();
        String sourceHint = ingestSourceHint(staged);
        String applyAnswer;
        if (config.review()) {
            String reviewPrompt = buildIngestReviewPrompt(config.topic(), staged, config.note().orElse(null));
            String reviewSummary = deps.runAgentReviewMode().apply(
                    workspaceDir, config.topic(), reviewPrompt, config.model().orElse(null));
            Map<String, Object> reviewMetadata = new LinkedHashMap<>();
            reviewMetadata.put("source_count", sourceCount);
            reviewMetadata.put("source_hint", sourceHint);
            LlmWikiLog.appendLogEntry(workspaceDir, "ingest.review", "completed", reviewMetadata, reviewSummary,
                    LlmWikiHelpers::writeIfMissing,
                    (path, content) -> LlmWikiHelpers.safeWrite(path, content));

            boolean approved = confirmIngestApply(reviewSummary, deps.askUser());
            if (!approved) {
                String cancelSummary = "Operator declined apply after ingest review.";
                Map<String, Object> cancelMetadata = new LinkedHashMap<>();
                cancelMetadata.put("source_count", sourceCount);
                cancelMetadata.put("source_hint", sourceHint);
                LlmWikiLog.appendLogEntry(workspaceDir, "ingest.apply", "canceled", cancelMetadata, cancelSummary,
                        LlmWikiHelpers::writeIfMissing,
                        (path, content) -> LlmWikiHelpers.safeWrite(path, content));
                return new IngestResult("Ingest canceled after review. No wiki changes were applied.", true);
            }
            String applyPrompt = buildIngestApplyPrompt(config.topic(), staged, reviewSummary, config.note().orElse(null));
            applyAnswer = deps.runAgentMode().apply(workspaceDir, config.topic(), applyPrompt, config.model().orElse(null));
        } else {
            String applyPrompt = buildIngestApplyPrompt(config.topic(), staged,
                    "No explicit review phase was run. First perform review-quality "
                            + "analysis (source extraction, change planning, contradiction checks), "
                            + "then apply updates directly.",
                    config.note().orElse(null));
            applyAnswer = deps.runAgentMode().apply(workspaceDir, config.topic(), applyPrompt, config.model().orElse(null));
        }
        LlmWikiIndex.refreshIndex(config.topic(), workspaceDir, LlmWikiHelpers::safeWrite);
        Map<String, Object> applyMetadata = new LinkedHashMap<>();
        applyMetadata.put("source_count", sourceCount);
        applyMetadata.put("source_hint", sourceHint);
        config.note().ifPresent(n -> applyMetadata.put("note", n));
        LlmWikiLog.appendLogEntry(workspaceDir, "ingest.apply", "applied", applyMetadata,
                applyAnswer == null || applyAnswer.isEmpty() ? "Ingest applied." : applyAnswer,
                LlmWikiHelpers::writeIfMissing,
                (path, content) -> LlmWikiHelpers.safeWrite(path, content));
        return new IngestResult(applyAnswer == null || applyAnswer.isEmpty() ? "Ingest applied." : applyAnswer, true);
    }

    /** Ask operator to approve ingest apply after the review phase. */
    public static boolean confirmIngestApply(String review, java.util.function.Function<String, String> askUser) {
        String reviewBlock = (review == null || review.isBlank())
                ? "(no review summary returned by model)" : review.strip();
        String prompt = "Ingest review summary:\n\n" + reviewBlock
                + "\n\nApply these wiki updates now? [y/N]: ";
        try {
            String response = askUser.apply(prompt);
            String trimmed = response == null ? "" : response.strip().toLowerCase();
            return trimmed.equals("y") || trimmed.equals("yes");
        } catch (Exception exc) {
            throw new LlmWikiHelpers.WikiError("Ingest review requires an interactive confirmation response.");
        }
    }
}
