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
