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

import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;

import java.util.List;
import java.util.Map;

/**
 * R390: "did the USER write this?" — the rule for a session's TITLE.
 *
 * <p>★ WHY THIS CLASS EXISTS ★
 *
 * <p>The engine writes several messages that carry {@link Role#USER} but
 * that the user never typed:
 *
 * <ul>
 *   <li>{@code kind=session-anchor} — the periodic anchor (R719);</li>
 *   <li>{@code kind=post-compact-context} — the "[PostCompact
 *       re-injection]" block (R718-C);</li>
 *   <li>{@code kind=compaction-side-note} / {@code transcript-trim-side-note}
 *       / {@code transcript-dedupe-side-note} — the pruning notices;</li>
 *   <li>{@code kind=todo-ask-llm} / {@code user-decision-resume} /
 *       {@code dedupe-stub} — the controller's own questions;</li>
 *   <li>the {@code <system-directive type="todo-continuation">} prompt — the
 *       per-todo controller's continuation, written WITHOUT a kind;</li>
 *   <li>the {@code "[Resuming after user decision]"} note — also written
 *       WITHOUT a kind.</li>
 * </ul>
 *
 * <p>pre-R390 the daemon derived a session's title by scanning the
 * transcript for the FIRST {@code "role":"user"} line. That line is very
 * often one of the messages above — so a session that had been compacted
 * showed "[PostCompact re-injection] The transcript was just compacted…" as
 * its name in the LeftPanel, and a session whose anchor happened to land
 * first showed "[R719 session-anchor] turn 7 / 5-stride.". The user's
 * actual prompt was never shown. That is the "long task finishes and the
 * session still has no name" symptom.
 *
 * <p>★ THE RULE ★ A user-role message is USER-AUTHORED iff:
 *
 * <ol>
 *   <li>it carries NO {@code kind} metadata — the user never sets a kind;
 *       every machine-authored message the engine writes is tagged with
 *       one; AND</li>
 *   <li>its text does not start with one of the engine's own text markers
 *       (the two machine-authored messages that carry no kind).</li>
 * </ol>
 *
 * <p>★ WHY THIS IS NOT {@link CodeCheckpointStore#isRealUserTurn} ★
 *
 * <p>That method answers a DIFFERENT question — "may a rewind land here?"
 * — and it deliberately errs the other way: an <em>unknown</em> kind
 * still counts as a turn, because dropping a turn shifts every rewind
 * target after it. This class errs the other way: an unknown kind is
 * machine-authored, because showing a machine message as the session's
 * name is a lie the user cannot act on. Two rules, two questions, two
 * conservative directions — and they are documented side by side so a
 * future round does not "unify" them by accident (Lesson 1207 — one
 * "list" can be several lists, and every one of them drifts).
 */
public final class UserAuthoredText {

    /**
     * The text markers the engine writes at the START of a machine-authored
     * user-role message. These two carry NO {@code kind} metadata, so the
     * kind check alone cannot see them.
     */
    private static final List<String> ENGINE_TEXT_MARKERS = List.of(
            // the per-todo controller's continuation prompt
            // (TodoContinuationHook.buildPrompt)
            "<system-directive",
            // the "[Resuming after user decision]" note
            // (QueryEngine — the post-decision continuation)
            "[Resuming after user decision]",
            // belt-and-braces: the anchor + post-compact blocks normally
            // carry a kind, but a transcript written by an older build
            // may have lost it.
            "[R719 session-anchor]",
            "[PostCompact re-injection]");

    private UserAuthoredText() {
    }

    /**
     * Is this message text the user actually wrote?
     *
     * @param m the message; {@code null} is not user-authored
     */
    public static boolean isUserAuthored(Message m) {
        if (m == null) return false;
        if (m.role() != Role.USER) return false;
        Map<String, Object> meta = m.metadata();
        Object kind = (meta == null) ? null : meta.get("kind");
        return isUserAuthored(kind == null ? null : String.valueOf(kind), m.textContent());
    }

    /**
     * Is this user-role text the user actually wrote?
     *
     * <p>The raw form, for callers that read a JSONL line instead of a
     * {@link Message} (the daemon's preview scanner). Keeping both forms
     * on ONE class is the point: the rule is written once.
     *
     * @param metadataKind the message's {@code kind} metadata, or null
     * @param text         the message's text, or null
     */
    public static boolean isUserAuthored(String metadataKind, String text) {
        // 1. The user never sets a kind. Anything tagged is machine-written.
        if (metadataKind != null && !metadataKind.isBlank()) return false;
        // 2. The two machine-written messages that carry no kind.
        if (text != null) {
            String t = text.stripLeading();
            for (String marker : ENGINE_TEXT_MARKERS) {
                if (t.startsWith(marker)) return false;
            }
        }
        return true;
    }

    /**
     * The metadata {@code kind} of a raw JSONL transcript line, or null
     * when the line carries no tagged metadata.
     *
     * <p>The metadata object is the LAST field of a serialized message, so
     * the scan starts from the end of the line. A cheap substring scan
     * beats a full JSON parse here — this runs once per session per
     * {@code listSessions} call, and the transcript can be megabytes.
     *
     * @param line one raw JSONL line
     * @return the {@code kind} value, or null
     */
    public static String metadataKindOf(String line) {
        if (line == null) return null;
        int idx = line.lastIndexOf("\"metadata\":");
        if (idx < 0) return null;
        int brace = line.indexOf('{', idx);
        if (brace < 0) return null;
        int end = line.indexOf('}', brace);
        if (end < 0) return null;
        String meta = line.substring(brace, end + 1);
        int k = meta.indexOf("\"kind\"");
        if (k < 0) return null;
        int colon = meta.indexOf(':', k);
        if (colon < 0) return null;
        int q = meta.indexOf('"', colon + 1);
        if (q < 0) return null;
        int close = meta.indexOf('"', q + 1);
        if (close < 0) return null;
        return meta.substring(q + 1, close);
    }
}
