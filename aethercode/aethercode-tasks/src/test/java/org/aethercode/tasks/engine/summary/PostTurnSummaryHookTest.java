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

package org.aethercode.tasks.engine.summary;

import org.aethercode.tasks.engine.summary.PostTurnSummaryHook.Kind;
import org.aethercode.tasks.engine.summary.PostTurnSummaryHook.SummaryResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-1-23 (6 tests): contract-level tests for the
 * {@link PostTurnSummaryHook} interface and the
 * {@link SummaryExtractor} / {@link SummaryFallback}
 * helpers. Implementation-level LLM tests live in
 * {@link LlmBackedSummaryHookTest}.
 */
class PostTurnSummaryHookTest {

    @Test
    void noopHookIsAlwaysSkipped() throws Exception {
        CompletableFuture<SummaryResult> fut = PostTurnSummaryHook.NOOP
                .onAssistantTurnEnd(new PostTurnSummaryHook.TurnContext(
                        "s1", "anything", java.util.List.of(), java.util.Map.of()));
        assertThat(fut.get().kind()).isEqualTo(Kind.SKIPPED);
    }

    @Test
    void summaryFallbackReturnsFallbackResult() throws Exception {
        SummaryFallback hook = new SummaryFallback("llm timeout");
        SummaryResult r = hook.onAssistantTurnEnd(new PostTurnSummaryHook.TurnContext(
                "s1", "...", java.util.List.of(), java.util.Map.of())).get();
        assertThat(r.kind()).isEqualTo(Kind.FALLBACK);
        assertThat(r.reason()).isEqualTo("llm timeout");
    }

    @Test
    void summaryExtractorRecognisesAllHeadingLevels() {
        assertThat(SummaryExtractor.hasSummaryBlock("Some text\n## Summary\nDid X.\n")).isTrue();
        assertThat(SummaryExtractor.hasSummaryBlock("Some text\n# Summary\nDid X.\n")).isTrue();
        assertThat(SummaryExtractor.hasSummaryBlock("Some text\n### Summary\nDid X.\n")).isTrue();
        assertThat(SummaryExtractor.hasSummaryBlock("## Summary:  \nDid X.\n")).isTrue();
    }

    @Test
    void summaryExtractorReturnsEmptyWhenAbsent() {
        assertThat(SummaryExtractor.hasSummaryBlock("just an answer")).isFalse();
        assertThat(SummaryExtractor.hasSummaryBlock(null)).isFalse();
        assertThat(SummaryExtractor.hasSummaryBlock("")).isFalse();
    }

    @Test
    void injectedFooterRendersSummaryBlock() {
        String footer = SummaryExtractor.renderInjectedFooter("Did A. Will do B.");
        assertThat(footer).contains("## Summary");
        assertThat(footer).contains("Did A. Will do B.");
    }

    @Test
    void failureFooterMatchesSpecWording() {
        // Spec wording: "Summary: (LLM auto-summary failed — see last assistant message)"
        String line = SummaryExtractor.renderFailureFooter(null);
        assertThat(line).contains("LLM auto-summary failed");
        assertThat(line).contains("see last assistant message");
    }
}
