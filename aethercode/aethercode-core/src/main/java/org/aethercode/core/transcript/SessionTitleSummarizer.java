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

package org.aethercode.core.transcript;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Stream;

/**
 * R393: give a session a SHORT title, written by the model.
 *
 * <p>★ WHY ★ Before R393 a session's title was literally the user's first
 * prompt — 40 characters of Chinese, or 400 of English — and the left rail
 * showed it verbatim. The user asked for "a 5-15 character summary, written
 * by the model" instead.
 *
 * <p>The class is deliberately small and side-effect-free apart from the
 * one write it is asked to make:
 * <ol>
 *   <li>{@link #buildPrompt(String)} — the instruction the model sees.</li>
 *   <li>{@link #sanitize(String)} — turn the model's answer into a title
 *       (strip decoration, enforce the length budget) or {@code null}.</li>
 *   <li>{@link #summarize(String)} — one-shot model call.</li>
 *   <li>{@link #applyTitle(String, String)} — persist, but ONLY into an
 *       empty slot.</li>
 * </ol>
 *
 * <p>★ WHY THE WRITE IS "ONLY INTO AN EMPTY SLOT" ★ The {@code title}
 * column holds the user's OWN rename. A model-written title must never
 * overwrite it — the user typed that name on purpose. The store enforces
 * this atomically ({@code SessionStoreSqlite.setTitleIfAbsent}), so a
 * rename that lands while the model call is in flight still wins.
 *
 * <p>★ WHY THE LENGTH RULE IS NOT ONE NUMBER ★ "5-15 characters" is a
 * Chinese-shaped rule: 15 Chinese characters fit in a rail row, but 15
 * Latin characters is three words ("Sort arrays"). So the budget is
 * applied on the CODE POINTS of a CJK-dominant title, and on a wider
 * budget for a Latin one — and the model is told which one it is being
 * asked for.
 */
public final class SessionTitleSummarizer {

    private static final Logger LOG = LoggerFactory.getLogger(SessionTitleSummarizer.class);

    /** The shortest title worth showing. Below this the title is noise. */
    public static final int MIN_TITLE_CHARS = 5;
    /** The budget for a CJK title — the user's "5 到 15 个字". */
    public static final int MAX_TITLE_CHARS = 15;
    /**
     * The budget for a NON-CJK title. 15 Latin characters is three words,
     * so Latin titles get a wider budget (≈ 8-10 English words).
     */
    public static final int MAX_TITLE_CHARS_LATIN = 60;
    /** How much of the first prompt the model is shown. */
    public static final int MAX_PROMPT_CHARS = 1500;

    /** The system prompt for the one-shot call. */
    public static final String TITLE_SYSTEM_PROMPT =
            "You write short session titles. You answer with the title only — "
            + "no preamble, no quotes, no numbering, no trailing punctuation.";

    private final ChatClient client;
    private final SessionStore store;

    public SessionTitleSummarizer(ChatClient client, SessionStore store) {
        this.client = client;
        this.store = store;
    }

    /**
     * The instruction the model sees.
     *
     * <p>★ WHY THE LANGUAGE RULE IS EXPLICIT ★ A MiniMax-M3 class model
     * follows English when the PROMPT is English, and the instruction
     * above is English — so without an explicit rule a Chinese request
     * would come back with an English title. (Same failure mode, same
     * fix, as R737's per-turn response-language directive.)
     */
    public static String buildPrompt(String firstPrompt) {
        String p = firstPrompt == null ? "" : firstPrompt.trim();
        if (p.length() > MAX_PROMPT_CHARS) {
            p = p.substring(0, MAX_PROMPT_CHARS) + "…";
        }
        return """
                给这个会话取一个简短的标题。

                规则：
                1. 只输出标题本身，不要解释、前言、引号、编号或结尾标点。
                2. 如果用户的请求是中文，标题必须是中文，5 到 15 个字。
                3. 如果用户的请求是其他语言，标题用该语言，3 到 8 个词。
                4. 概括这个会话「在做什么」，不要逐字复述第一句话。
                5. 不要把文件名、路径或代码标识符放进标题，除非它们本身就是任务的核心。

                用户的请求：
                """ + p;
    }

    /**
     * Turn the model's answer into a title.
     *
     * @return the title, or {@code null} when the answer is unusable
     *         (empty, too short, or nothing but decoration)
     */
    public static String sanitize(String raw) {
        return sanitize(raw, null);
    }

    /**
     * Turn the model's answer into a title — and refuse a title that is
     * just the prompt again.
     *
     * @param raw           the model's answer
     * @param sourcePrompt  the prompt the title was made FROM (may be
     *                      {@code null}) — a title that is a literal
     *                      prefix of the prompt is a COPY, not a summary
     * @return the title, or {@code null} when the answer is unusable
     */
    public static String sanitize(String raw, String sourcePrompt) {
        if (raw == null) return null;
        // ★ R393 fix (real-machine finding): the answer arrives with the
        // model's REASONING in front of it. MiniMax-M3 is a reasoning
        // model and the transport delivers its `<think>…</think>`
        // preamble in the same text stream as the answer — so the raw
        // text starts with the model's own planning notes:
        //
        //    <think>We need title only Chinese 5-15 chars, summarize…</think>创建排序项目
        //
        // The repository already knows this shape — `aethercode-evals`'
        // `BenchmarkLlmAgent` strips `<think>…</think>` for exactly this
        // reason. So the strip happens FIRST, on the whole answer, and
        // only then do we take the first non-empty line: stripping
        // afterwards would leave an empty first line and the title
        // would fall back to the user's whole prompt again.
        String s = stripReasoning(raw);
        // The first non-empty line — the model sometimes adds an
        // explanation on the second line.
        s = firstNonEmptyLine(s);
        if (s == null) return null;
        // Leading decoration: markdown bullets, heading marks, block quotes.
        s = s.replaceAll("^[\\s#>*\\-+]+", "");
        s = s.replace("**", "").replace("__", "").replace("`", "");
        s = s.replaceAll("[*_~]", "");
        s = s.trim();
        // Surrounding quotes — several pairs, because the model picks
        // whichever the request's language suggests.
        s = stripPaired(s, '"', '"');
        s = stripPaired(s, '\'', '\'');
        s = stripPaired(s, '\u201c', '\u201d');
        s = stripPaired(s, '\u2018', '\u2019');
        s = stripPaired(s, '\u300c', '\u300d');
        s = stripPaired(s, '\u300e', '\u300f');
        s = stripPaired(s, '\u300a', '\u300b');
        s = stripPaired(s, '\u3010', '\u3011');
        s = s.trim();
        // Trailing punctuation and whitespace.
        s = s.replaceAll("[\\s\u3002\uff0e.!?,?\uff01\uff1f,\u3001;:\uff1b:\uff1a]+$", "");
        // Collapse inner whitespace.
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;
        // The length budget.
        boolean cjk = isCjkDominant(s);
        int max = cjk ? MAX_TITLE_CHARS : MAX_TITLE_CHARS_LATIN;
        if (s.length() > max) {
            s = s.substring(0, max);
            if (!cjk) {
                // Don't leave half a word at the end.
                int sp = s.lastIndexOf(' ');
                if (sp > 0) s = s.substring(0, sp);
            }
            s = s.trim();
        }
        if (s.length() < MIN_TITLE_CHARS) return null;
        // ★ A title that is a literal PREFIX of the prompt is a copy, not
        // a summary. The model sometimes ignores the instruction and
        // answers with the request itself; writing that back would put
        // the very thing R393 removes (the whole prompt) into the title
        // slot, and the "empty slot" guard would then keep it there
        // forever. Refuse it and leave the slot empty.
        if (sourcePrompt != null && !sourcePrompt.isBlank()) {
            String p = sourcePrompt.trim();
            if (p.startsWith(s)) return null;
        }
        return s;
    }

    /**
     * One-shot model call.
     *
     * @return the title, or {@code null} when the model is unavailable,
     *         the call failed, or the answer is unusable
     */
    public String summarize(String firstPrompt) {
        if (client == null || firstPrompt == null || firstPrompt.isBlank()) return null;
        List<Message> messages = List.of(Message.userText(buildPrompt(firstPrompt)));
        StringBuilder out = new StringBuilder();
        boolean[] failed = {false};
        try (Stream<StreamEvent> stream = client.stream(messages, TITLE_SYSTEM_PROMPT, List.of())) {
            stream.forEach(ev -> {
                if (ev instanceof StreamEvent.TextDelta td) {
                    out.append(td.text());
                } else if (ev instanceof StreamEvent.RunEnd re) {
                    // The transport reports failures as a terminal event
                    // rather than by throwing. `isErrorOutcome()` is the
                    // engine's OWN classification (R743/R750d) — not a
                    // hand-rolled prefix match.
                    if (re.isErrorOutcome()) {
                        failed[0] = true;
                    }
                }
            });
        } catch (RuntimeException e) {
            LOG.debug("R393: session title call failed: {}", e.getMessage());
            return null;
        }
        if (failed[0]) {
            LOG.debug("R393: session title call ended with an error");
            return null;
        }
        return sanitize(out.toString(), firstPrompt);
    }

    /**
     * Persist the title — but only into an EMPTY slot, so the user's own
     * rename always wins.
     *
     * @return {@code true} when the title was written
     */
    public boolean applyTitle(String sessionId, String title) {
        if (store == null || sessionId == null || sessionId.isBlank()) return false;
        if (title == null || title.isBlank()) return false;
        try {
            return store.setTitleIfAbsent(sessionId, title);
        } catch (java.io.IOException e) {
            LOG.debug("R393: could not persist the title of {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String firstNonEmptyLine(String raw) {
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) return t;
        }
        return null;
    }

    /**
     * ★ R393 fix (real-machine finding): remove the model's REASONING
     * preamble from the answer.
     *
     * <p>Three shapes have to be handled:
     * <ol>
     *   <li>{@code <think>…</think>} — a complete reasoning block. The
     *       answer is AFTER it. (The common one.)</li>
     *   <li>{@code <think>…} — an UNTERMINATED block: the answer never
     *       arrived, or the stream was cut. Everything from the opener
     *       on is reasoning, so it is dropped.</li>
     *   <li>{@code …</think>} — a stray closing tag with no opener
     *       (some transports deliver the reasoning separately and only
     *       leak the closing tag). It is dropped, the rest is kept.</li>
     * </ol>
     *
     * <p>The regex is the repository's own convention — see
     * {@code aethercode-evals}' {@code BenchmarkLlmAgent}, which strips
     * {@code (?is)<think>.*?</think>} for the same provider.
     */
    static String stripReasoning(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("(?is)<think>.*?</think>", " ");
        // An opener with no closer: everything from it on is reasoning.
        int open = indexOfIgnoreCase(s, "<think>");
        if (open >= 0) s = s.substring(0, open);
        // A closer with no opener.
        s = s.replaceAll("(?i)</think>", " ");
        return s;
    }

    private static int indexOfIgnoreCase(String s, String needle) {
        return s.toLowerCase(java.util.Locale.ROOT).indexOf(needle.toLowerCase(java.util.Locale.ROOT));
    }

    private static String stripPaired(String s, char open, char close) {
        if (s.length() >= 2 && s.charAt(0) == open && s.charAt(s.length() - 1) == close) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /**
     * Is this title CJK-shaped? (CJK codepoints against Latin letters.)
     *
     * <p>Mirrors R737's detection, with a threshold that suits a title:
     * a 6-character Chinese title has 0 Latin letters, so a "CJK &ge; 12"
     * rule (right for a whole prompt) would call it English.
     */
    public static boolean isCjkDominant(String s) {
        if (s == null || s.isEmpty()) return false;
        int cjk = 0;
        int latin = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjkCodePoint(cp)) cjk++;
            else if (Character.isLetter(cp)) latin++;
        }
        return cjk > 0 && cjk * 2 >= latin;
    }

    private static boolean isCjkCodePoint(int cp) {
        return (cp >= 0x3000 && cp <= 0x303F)     // CJK punctuation
                || (cp >= 0x3040 && cp <= 0x30FF) // kana
                || (cp >= 0x3400 && cp <= 0x4DBF) // CJK ext A
                || (cp >= 0x4E00 && cp <= 0x9FFF) // CJK unified
                || (cp >= 0xAC00 && cp <= 0xD7AF) // hangul
                || (cp >= 0xF900 && cp <= 0xFAFF) // CJK compat
                || (cp >= 0xFF00 && cp <= 0xFFEF); // fullwidth forms
    }
}
