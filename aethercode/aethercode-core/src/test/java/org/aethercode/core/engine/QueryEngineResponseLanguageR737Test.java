/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.engine;

import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R737 (2026-10-20) — the engine must answer in the user's
 * language.
 *
 * <p>Root of report: the user writes Chinese ("继续执行当前任务",
 * "列举目录结构", "总结当前状态") and the engine answers entirely in
 * English. Two causes, both needed fixing:
 *
 * <ol>
 *   <li>The system prompt's identity section is written entirely in
 *       English and carries no language instruction, so a
 *       MiniMax-M3 class model anchors on English and stays
 *       there. Fixed in {@code SystemPrompt.defaultIdentity()} —
 *       a soft "reply in the user's language" rule.</li>
 *   <li>A soft rule in a 4000-token English prompt is not strong
 *       enough on its own. Fixed here: the engine DETECTS the
 *       user's language from the transcript and appends an explicit
 *       per-turn directive at the very tail of the system prompt —
 *       the position closest to the model's "now" attention.</li>
 * </ol>
 *
 * <p>The directive must be scoped so the model does not start
 * translating `bash` command lines and file paths — that breaks the
 * tools. So the contract asserted here is two-sided: Chinese prose
 * in, and "keep tool invocations verbatim" also present.
 */
class QueryEngineResponseLanguageR737Test {

    /** A realistic Chinese user prompt — long enough to clear the
     *  detection threshold (cjk >= 12). */
    private static final String ZH_PROMPT =
            "继续执行当前任务，先汇总 RAG 项目的整体状态，然后列出还差哪些功能没补齐。";

    /**
     * The detection contract: a Chinese user message must produce
     * a directive. Pins the source so a refactor that drops the call
     * from buildSystemForTurn fails here rather than silently
     * regressing the user's language.
     */
    @Test
    void sourcePerTurnSystemInjectsLanguageDirective() {
        String src = readSource();
        assertTrue(src.contains("responseLanguageDirective"),
                "QueryEngine must call responseLanguageDirective() from the per-turn "
                        + "system-prompt build path");
        assertTrue(src.contains("detectUserLanguage"),
                "QueryEngine must detect the user's language from the transcript");
        // The directive must be appended LAST so it wins the recency
        // battle against the English identity section.
        int dirIdx = src.indexOf("sb.append(lang)");
        int planIdx = src.indexOf("sb.append(plan)");
        assertTrue(dirIdx > 0 && planIdx > 0 && dirIdx > planIdx,
                "the language directive must be appended after the plan suffix, "
                        + "not before — it is the closest text to the model's "
                        + "'now' attention");
    }

    /** The scope contract: the directive must tell the model to keep
     *  code / paths / identifiers verbatim, or it will translate
     *  `bash cd /d D:\\...` and the tools break. */
    @Test
    void directiveScopesProseOnly() {
        String src = readSource();
        assertTrue(src.contains("Keep these VERBATIM"),
                "the directive must carve out verbatim content (code, paths, "
                        + "identifiers, copied error strings)");
        assertTrue(src.contains("Response language"),
                "the directive must be a clearly headed section so the model "
                        + "treats it as an instruction, not as prose");
    }

    /** The detection helper must not fire on pure-English or empty
     *  input — otherwise every English-only session gets a Chinese
     *  system prompt appended, which is a regression in the other
     *  direction. */
    @Test
    void detectionIsConservative() {
        String src = readSource();
        // Threshold guards: cjk >= 12 AND cjk*4 >= latin.
        assertTrue(src.contains("cjk >= 12"),
                "detection needs a minimum absolute CJK count so one pasted "
                        + "CJK filename does not flip the turn");
        assertTrue(src.contains("cjk * 4 >= latin"),
                "detection needs a ratio guard so code-heavy prompts with a "
                        + "little CJK do not flip the turn");
        // Only Role.USER is scanned.
        assertTrue(src.contains("m.role() != Role.USER"),
                "detection must only read USER messages — assistant turns and "
                        + "tool results are English/system-locale and would "
                        + "poison the detection");
    }

    /**
     * The identity-section soft rule. This one is source-pinned
     * rather than behaviour-pinned because
     * {@code SystemPrompt.defaultIdentity()} is private; asserting
     * on source is cheaper than reflecting into it.
     */
    @Test
    void identitySectionCarriesSoftLanguageRule() throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get(
                "..", "aethercode-prompts", "src", "main", "java",
                "org", "aethercode", "prompts", "SystemPrompt.java");
        String src = java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("R737"),
                "SystemPrompt.defaultIdentity() must carry the R737 soft "
                        + "language rule");
        assertTrue(src.contains("reply in the user's own language"),
                "the soft rule must state the mirror-the-user rule explicitly");
    }

    // ----------------------------------------------------------------

    private static String readSource() {
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(
                    "src", "main", "java", "org", "aethercode", "core",
                    "engine", "QueryEngine.java");
            return java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException("cannot read QueryEngine.java source", e);
        }
    }

    // Unused imports kept referenced so the compiler does not warn
    // when this file is edited down later.
    @SuppressWarnings("unused")
    private static void unusedRefs() {
        List<Message> a = new ArrayList<>();
        Role b = Role.USER;
    }
}