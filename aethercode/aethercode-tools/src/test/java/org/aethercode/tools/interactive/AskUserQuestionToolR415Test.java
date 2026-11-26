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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R415: THE QUESTION TIMEOUT IS CONFIGURABLE.
 *
 * <h2>★ WHY R415 TOUCHED IT ★</h2>
 * <p>{@code ask_user_question} waits {@link AskUserQuestionTool#DEFAULT_TIMEOUT_MS}
 * (120 s) for an answer that arrives through the UI. The scenario matrix has
 * no UI: nothing ever answers, so a turn that asks the user burns 120 s —
 * and the model asks <em>again</em>, so the L1 scenario spent six minutes
 * waiting for a question nobody could answer, inside a 900 s turn budget.
 *
 * <p>⇒ The knob is an ENV var, like the other escape hatches in this
 * codebase ({@code AETHERCODE_ALLOW_ANY_PATH},
 * {@code AETHERCODE_BASH_ALLOW_SCOPE_ESCAPE}). The default does not move:
 * a real user still gets two minutes to answer.
 *
 * <h2>★ THE MUTATION WITNESSES ★</h2>
 * <ul>
 *   <li>M95 — the environment value ignored ⇒ the parsing pin;</li>
 *   <li>M96 — the call waits the DEFAULT instead of the configured timeout ⇒
 *       the "gives up on time" pin.</li>
 * </ul>
 */
class AskUserQuestionToolR415Test {

    @Test
    void theTimeoutIsReadFromTheEnvironment() {
        // ★ THE WIRING, NOT THE PARSER ★ the mutation battery found the
        // hole: with the wiring mutated to ignore the environment, a test
        // that only called the parser stayed green — in a test JVM the
        // variable is absent either way. ⇒ this test stands in for the
        // environment and asks the WIRING.
        java.util.function.Function<String, String> old = AskUserQuestionTool.envReader;
        try {
            AskUserQuestionTool.envReader =
                    (name) -> AskUserQuestionTool.ENV_TIMEOUT.equals(name) ? "5000" : null;
            assertEquals(5_000L, AskUserQuestionTool.readTimeoutMs(),
                    "the timeout must come from the environment variable");
            AskUserQuestionTool.envReader =
                    (name) -> AskUserQuestionTool.ENV_TIMEOUT.equals(name) ? "not-a-number" : null;
            assertEquals(120_000L, AskUserQuestionTool.readTimeoutMs(),
                    "an unusable environment value keeps the default");
        } finally {
            AskUserQuestionTool.envReader = old;
        }
    }

    @Test
    void theTimeoutValueIsParsedLeniently() {
        assertEquals(120_000L, AskUserQuestionTool.parseTimeoutMs(null, 120_000L),
                "an absent value keeps the default");
        assertEquals(120_000L, AskUserQuestionTool.parseTimeoutMs("   ", 120_000L),
                "a blank value keeps the default");
        assertEquals(5_000L, AskUserQuestionTool.parseTimeoutMs("5000", 120_000L),
                "a plain number is the timeout");
        assertEquals(5_000L, AskUserQuestionTool.parseTimeoutMs("  5000  ", 120_000L),
                "the value is trimmed");
        assertEquals(120_000L, AskUserQuestionTool.parseTimeoutMs("abc", 120_000L),
                "a value that is not a number keeps the default");
        assertEquals(120_000L, AskUserQuestionTool.parseTimeoutMs("0", 120_000L),
                "zero is not a timeout");
        assertEquals(120_000L, AskUserQuestionTool.parseTimeoutMs("-1", 120_000L),
                "a negative timeout is not a timeout");
    }

    @Test
    void theCallGivesUpAfterTheConfiguredTimeout() {
        // ★ THE POINT OF THE KNOB ★ a harness (or a deployment) that cannot
        // answer must not wait two minutes per question.
        long old = AskUserQuestionTool.timeoutMs();
        try {
            AskUserQuestionTool.setTimeoutMs(200);
            long t0 = System.currentTimeMillis();
            Tool.ToolResult r = AskUserQuestionTool.call(
                    Map.of("questions", List.of(
                            Map.of("question", "where?", "options", List.of(Map.of("label", "here"))))),
                    Tool.CallContext.of("r415"));
            long dt = System.currentTimeMillis() - t0;
            assertTrue(r.isError(),
                    "an unanswered question must come back as an error, not as an answer: " + r.output());
            assertTrue(dt < 10_000,
                    "the call must give up after the CONFIGURED timeout, not the default (it took "
                            + dt + "ms)");
            assertTrue(String.valueOf(r.output()).contains("200"),
                    "the error must name the timeout it waited: " + r.output());
        } finally {
            AskUserQuestionTool.setTimeoutMs(old);
        }
    }
}
