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

package org.aethercode.tools.interactive;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ask the user a question. Mirrors the TS {@code AskUserQuestionTool}. This is the
 * canonical example of a "the model wants to ask the human" tool: the framework
 * short-circuits the call until a UI handler resolves it.
 *
 * <p>Wire it via {@link #setPendingAsk(PendingAsk)} from the TUI / IDEA panel. The result
 * is whatever the user picked; if no handler is set within a reasonable time, the tool
 * surfaces a clear error so the model can fall back to a sensible default.
 */
public class AskUserQuestionTool {

    public static final String NAME = "ask_user_question";
    public static final String PENDING_KEY = "_ask_user_question_pending";
    public static final long DEFAULT_TIMEOUT_MS = 120_000;

    /**
     * ★ R415: THE ENV NAME ★ the variable that shortens the wait.
     */
    static final String ENV_TIMEOUT = "AETHERCODE_ASK_TIMEOUT_MS";

    /**
     * ★ R415: THE ENV SEAM ★ a JVM cannot change its own environment, so a
     * test that wants to prove the **wiring** — that the value really
     * comes from the process environment — needs a seam to stand in for
     * it.
     *
     * <p>★ The mutation battery found this by finding the hole: with the
     * wiring mutated to ignore the environment, a test that only exercised
     * the PARSER stayed green — because in a test JVM the variable is
     * absent either way, and both versions answer the same. ⇒ a test that
     * asks "is the parser right" does not ask "is the parser WIRED UP".
     *
     * <p>★ DECLARED BEFORE {@link #timeoutMs} ★ Lesson 1611, hit again in
     * the same round: a static field declared LATER is still null while the
     * earlier field's initialiser runs, so {@code timeoutMs = readTimeoutMs()}
     * read a null seam and the class failed to initialise at all.
     */
    static volatile java.util.function.Function<String, String> envReader = System::getenv;

    /**
     * ★ R415: THE TIMEOUT IS CONFIGURABLE ★
     *
     * <p>The tool waits {@link #DEFAULT_TIMEOUT_MS} for an answer that
     * arrives through the UI. A host that cannot answer — the scenario
     * matrix has no UI — paid for that wait on every question: R415's live
     * run showed the model asking <em>three</em> times, six minutes of
     * wall clock for a question nobody could answer, inside a 900 s turn
     * budget.
     *
     * <p>⇒ The knob is an ENV var, like the other escape hatches in this
     * codebase ({@code AETHERCODE_ALLOW_ANY_PATH},
     * {@code AETHERCODE_BASH_ALLOW_SCOPE_ESCAPE}). The default does NOT
     * move: a real user still gets two minutes to answer.
     */
    private static volatile long timeoutMs = readTimeoutMs();

    /**
     * Parse the ENV value. A value that is not a positive number keeps the
     * fallback — a typo in a deployment must not turn "wait two minutes"
     * into "wait forever" or "give up at once".
     *
     * @param raw      the raw ENV value, may be null
     * @param fallback what to keep when the value is unusable
     * @return the timeout in milliseconds
     */
    static long parseTimeoutMs(String raw, long fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static long readTimeoutMs() {
        return parseTimeoutMs(envReader.apply(ENV_TIMEOUT), DEFAULT_TIMEOUT_MS);
    }

    /** the timeout this tool will wait, in milliseconds. */
    public static long timeoutMs() { return timeoutMs; }

    /** set the timeout (tests, embedding hosts). Non-positive keeps the default. */
    public static void setTimeoutMs(long v) {
        timeoutMs = v > 0 ? v : DEFAULT_TIMEOUT_MS;
    }

    private static final AtomicReference<PendingAsk> CURRENT = new AtomicReference<>();

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();

        var questionProps = new LinkedHashMap<String, Map<String, Object>>();
        questionProps.put("question", Tools.stringProp("The question text."));
        var options = new LinkedHashMap<String, Map<String, Object>>();
        options.put("label",       Tools.stringProp("Short label shown to the user."));
        options.put("description", Tools.stringProp("Optional longer description."));
        options.put("preview",     Tools.stringProp("Optional preview snippet."));
        questionProps.put("options", Tools.objectSchema(new LinkedHashMap<>(options), "label"));
        questionProps.put("header",  Tools.stringProp("Optional one-word category shown above the options."));
        questionProps.put("multi_select", Tools.boolProp("If true, user can pick multiple."));

        var questions = new LinkedHashMap<String, Object>();
        questions.put("type", "array");
        questions.put("items", Tools.objectSchema(new LinkedHashMap<>(questionProps), "question", "options"));

        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("questions", questions);
        schema.put("type", "object");
        schema.put("properties", sp);
        schema.put("required", List.of("questions"));

        return Tools.build(new ToolDef(
                NAME,
                "Ask the user one or more multiple-choice questions. Pauses the agent loop " +
                        "until the user answers. Use sparingly — only when you genuinely cannot " +
                        "proceed without human input.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        Object qs = input.get("questions");
        if (!(qs instanceof List<?> list) || list.isEmpty()) {
            return Tool.ToolResult.error("questions is required and must be a non-empty list");
        }
        PendingAsk ask = new PendingAsk(list);
        CURRENT.set(ask);
        ctx.setExtra(PENDING_KEY, ask);

        long wait = timeoutMs;
        long deadline = System.currentTimeMillis() + wait;
        while (ask.answer == null && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        if (ask.answer == null) {
            return Tool.ToolResult.error("user did not answer within " + waitText(wait));
        }
        return Tool.ToolResult.of(ask.answer);
    }

    /** the timeout as the user reads it: seconds, or milliseconds under a second. */
    static String waitText(long ms) {
        return ms >= 1000 ? (ms / 1000) + "s" : ms + "ms";
    }

    public static boolean isReadOnly(Map<String, Object> input) { return true; }

    /** UI-side entry point: provide the user's answer. */
    public static void setPendingAsk(PendingAsk ask) { CURRENT.set(ask); }

    /** UI-side entry point: read the pending ask (if any). */
    public static PendingAsk current() { return CURRENT.get(); }

    /** Resolves a single ask. */
    public static final class PendingAsk {
        public final List<?> questions;
        public volatile String answer;
        public PendingAsk(List<?> questions) { this.questions = questions; }
        public void answer(String a) { this.answer = a; }
    }
}
