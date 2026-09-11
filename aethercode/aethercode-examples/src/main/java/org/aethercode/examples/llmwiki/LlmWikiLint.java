package org.aethercode.examples.llmwiki;

import java.nio.file.Path;

/**
 * Lint-specific workflow for the LLM wiki.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/llm-wiki/lint.py}. Builds the
 * single-pass lint prompt and runs the agent mode against the
 * pulled workspace, then refreshes the index and appends a
 * structured lint timeline entry.</p>
 */
public final class LlmWikiLint {
    private LlmWikiLint() {}

    /** Build the single-pass lint prompt. Mirrors the Python port's {@code build_lint_prompt}. */
    public static String buildLintPrompt(String topic, String note) {
        String noteText = note == null ? "(none)" : note;
        return "Run a single-pass lint reconciliation for the '" + topic + "' wiki under `/wiki/`.\n\n"
                + "Execution mode:\n"
                + "- Read recent `/log.md` entries first (latest ~10 `## [` headings) to account for recent work.\n"
                + "- Apply updates immediately in this run (no review/confirm phase).\n"
                + "- Update wiki pages in place; do not create a separate lint report directory.\n"
                + "- You may create new canonical wiki pages when required for reconciliation.\n"
                + "- Do not edit `/log.md`; the runner appends structured lint timeline entries.\n"
                + "- Never write to `/raw/`.\n\n"
                + "Required health checks and fixes:\n"
                + "- Reconcile contradictions across wiki pages and preserve explicit uncertainty when unresolved.\n"
                + "- Identify stale claims superseded by newer evidence and update or qualify those claims.\n"
                + "- Detect orphan pages with no inbound links and add/repair cross-references or merge them.\n"
                + "- Add missing cross-references between related pages and concepts.\n"
                + "- When an important concept lacks a dedicated page, create a canonical page and link it.\n"
                + "- Identify data gaps and missing evidence that block confidence.\n"
                + "- Suggest high-value follow-up questions and source leads for unresolved gaps.\n\n"
                + "External verification policy:\n"
                + "- Use model-native web browsing/search only if available in this model/runtime.\n"
                + "- If web access is unavailable, do not fabricate findings; mark gaps as unresolved and list what to verify next.\n\n"
                + "After edits, return a concise markdown report with exactly these sections:\n"
                + "## Reconciled Changes\n"
                + "## Remaining Gaps\n"
                + "## Suggested Next Questions and Sources\n\n"
                + "Operator note: " + noteText + "\n";
    }

    /**
     * Run lint mode as a single-pass apply and return the lint
     * summary. Mirrors the Python port's {@code run_lint_workspace}.
     */
    public static String runLintWorkspace(
            LlmWikiModels.RunnerConfig config,
            Path workspaceDir,
            LlmWikiModels.CliDeps deps) {
        String prompt = buildLintPrompt(config.topic(), config.note().orElse(null));
        String lintSummary = deps.runAgentMode().apply(workspaceDir, config.topic(), prompt, config.model().orElse(null));
        LlmWikiIndex.refreshIndex(config.topic(), workspaceDir, LlmWikiHelpers::safeWrite);
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        config.note().ifPresent(n -> metadata.put("note", n));
        LlmWikiLog.appendLogEntry(workspaceDir, "lint.apply", "applied", metadata,
                (lintSummary == null || lintSummary.isBlank()) ? "Lint applied without model summary." : lintSummary,
                LlmWikiHelpers::writeIfMissing,
                (path, content) -> LlmWikiHelpers.safeWrite(path, content));
        String summary = lintSummary == null ? "" : lintSummary.strip();
        if (!summary.isEmpty()) return summary;
        return "## Reconciled Changes\n- Lint applied.\n\n## Remaining Gaps\n- None reported.\n\n## Suggested Next Questions and Sources\n- None reported.";
    }
}
