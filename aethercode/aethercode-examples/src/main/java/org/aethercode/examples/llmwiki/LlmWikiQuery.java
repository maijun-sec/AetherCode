package org.aethercode.examples.llmwiki;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query-specific workflow for the LLM wiki.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/llm-wiki/query.py}. Builds the
 * read-only query prompt, parses the agent's {@code FILING_DECISION}
 * and {@code FILING_REASON} markers, and (when the model opts to
 * file) writes a durable query page.</p>
 */
public final class LlmWikiQuery {
    private LlmWikiQuery() {}

    private static final Pattern DECISION_PATTERN = Pattern.compile(
            "^FILING_DECISION:\\s*(file|skip)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern REASON_PATTERN = Pattern.compile(
            "^FILING_REASON:\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** Result from one query workspace pass. */
    public record QueryResult(String answer, boolean shouldPush, Optional<String> filedPath) {}

    /** Parsed decision output from query analysis. */
    public record QueryDecision(String answer, boolean shouldFile, String reason) {}

    /** Create a stable slug for query filing pages. */
    public static String querySlug(String question) {
        String slug = LlmWikiHelpers.slugifyTopic(question == null ? "" : question);
        if (slug.length() > 80) slug = slug.substring(0, 80).replaceAll("-+$", "");
        return slug.isEmpty() ? "query" : slug;
    }

    /** Return the canonical wiki path for a filed query answer. */
    public static String queryTargetPath(String question) {
        return "/wiki/query/" + querySlug(question) + ".md";
    }

    /** Build the read-only query prompt with filing decision output. */
    public static String buildQueryPrompt(String topic, String question) {
        return "Answer this question about '" + topic + "': " + question + "\n\n"
                + "This is analysis-only. Do not create, edit, move, or delete files.\n\n"
                + "Required workflow:\n"
                + "1) Read `/wiki/index.md` first and use its categorized summaries/metadata to "
                + "choose candidate pages.\n"
                + "2) Read recent `/log.md` entries (latest ~10 `## [` headings) to understand what "
                + "was ingested, queried, or linted recently.\n"
                + "3) Prefer checking relevant prior `/wiki/query/*.md` pages first as a discovery step.\n"
                + "4) Use those query pages to identify likely canonical `/wiki/*.md` pages and topics.\n"
                + "5) Read the canonical wiki pages before final synthesis.\n"
                + "6) Provide a grounded answer with wiki file path citations.\n"
                + "7) Decide whether this answer should be filed as a durable wiki page.\n\n"
                + "Evidence policy:\n"
                + "- Treat `/log.md` as operational recency context, not primary factual evidence.\n"
                + "- Treat `/wiki/query/*.md` pages as routing hints, not primary evidence.\n"
                + "- Cite canonical wiki pages for final claims whenever possible.\n"
                + "- If a claim is only supported by query pages, explicitly note uncertainty and missing canonical grounding.\n\n"
                + "Filing policy:\n"
                + "- Choose `file` when the answer has durable reuse value for future research.\n"
                + "- Choose `skip` for ad-hoc or low-reuse answers.\n\n"
                + "Output format (exact keys):\n"
                + "ANSWER:\n"
                + "<markdown answer with citations>\n\n"
                + "FILING_DECISION: file|skip\n"
                + "FILING_REASON: <one sentence>\n";
    }

    /** Parse query decision markers from model output. */
    public static QueryDecision parseQueryDecision(String rawResponse) {
        String response = rawResponse == null ? "" : rawResponse.strip();
        Matcher dm = DECISION_PATTERN.matcher(response);
        Matcher rm = REASON_PATTERN.matcher(response);
        boolean shouldFile = dm.find() && "file".equalsIgnoreCase(dm.group(1));
        String reason = rm.find()
                ? rm.group(1).strip()
                : "Decision marker missing; defaulted to skip.";
        String answer = response;
        if (dm.find()) answer = response.substring(0, dm.start()).strip();
        if (answer.toUpperCase().startsWith("ANSWER:")) {
            answer = answer.substring("ANSWER:".length()).strip();
        }
        if (answer.isEmpty()) answer = response.isEmpty() ? "No answer returned." : response;
        return new QueryDecision(answer, shouldFile, reason);
    }

    /** Build the query filing prompt for durable wiki page updates. */
    public static String buildQueryApplyPrompt(String topic, String question, String answerDraft,
                                                String filingReason, String targetPath) {
        return "File a durable query answer for topic '" + topic + "'.\n\n"
                + "Create or overwrite exactly: `" + targetPath + "`\n\n"
                + "Requirements:\n"
                + "1) Write a clean, scannable markdown page at the target path.\n"
                + "2) Preserve grounded claims and include wiki file path citations.\n"
                + "3) Include these sections: `Question`, `Answer`, and `Sources`.\n"
                + "4) Keep the answer focused and useful for future reuse.\n"
                + "5) Never write to `/raw/`.\n\n"
                + "Filing reason: " + filingReason + "\n\n"
                + "Question: " + question + "\n\n"
                + "Answer draft:\n" + answerDraft + "\n";
    }

    /** Run query mode and optionally file durable answers into the wiki. */
    public static QueryResult runQueryWorkspace(
            LlmWikiModels.RunnerConfig config,
            Path workspaceDir,
            LlmWikiModels.CliDeps deps) {
        String question = config.question().orElse("");
        String reviewPrompt = buildQueryPrompt(config.topic(), question);
        String reviewResponse = deps.runAgentReviewMode().apply(
                workspaceDir, config.topic(), reviewPrompt, config.model().orElse(null));
        QueryDecision decision = parseQueryDecision(reviewResponse);
        String reviewOutcome = decision.shouldFile() ? "file" : "skip";
        Map<String, Object> reviewMetadata = new LinkedHashMap<>();
        reviewMetadata.put("question", question);
        reviewMetadata.put("decision", reviewOutcome);
        LlmWikiLog.appendLogEntry(workspaceDir, "query.review", reviewOutcome, reviewMetadata, decision.answer(),
                LlmWikiHelpers::writeIfMissing,
                (path, content) -> LlmWikiHelpers.safeWrite(path, content));
        if (!decision.shouldFile()) {
            return new QueryResult(decision.answer(), true, Optional.empty());
        }
        String targetPath = queryTargetPath(question);
        String applyPrompt = buildQueryApplyPrompt(
                config.topic(), question, decision.answer(), decision.reason(), targetPath);
        deps.runAgentMode().apply(workspaceDir, config.topic(), applyPrompt, config.model().orElse(null));
        LlmWikiIndex.refreshIndex(config.topic(), workspaceDir, LlmWikiHelpers::safeWrite);
        Map<String, Object> applyMetadata = new LinkedHashMap<>();
        applyMetadata.put("question", question);
        applyMetadata.put("path", targetPath);
        LlmWikiLog.appendLogEntry(workspaceDir, "query.apply", "filed", applyMetadata, decision.answer(),
                LlmWikiHelpers::writeIfMissing,
                (path, content) -> LlmWikiHelpers.safeWrite(path, content));
        return new QueryResult(decision.answer(), true, Optional.of(targetPath));
    }
}
