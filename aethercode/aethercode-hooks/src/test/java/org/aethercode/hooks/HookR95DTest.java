package org.aethercode.hooks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * tests for the new {@code Hook.Kind} values
 * ({@code PRE_MODEL_QUERY}, {@code POST_STREAM_END}) and the
 * new {@code Outcome.Async} verdict. Also covers the
 * {@code HookContext.forPreModelQuery} / {@code forPostStreamEnd}
 * factory methods.
 *
 * <p>The previous kinds/outcomes are still tested in their
 * own classes (HookTest, HookRegistryTest,
 * ContinueWithResultMultiFieldTest) — this class is strictly
 * additive.
 */
class HookR95DTest {

    // -------------------------------------------------------------------
    //  Kinds: PRE_MODEL_QUERY and POST_STREAM_END exist
    // -------------------------------------------------------------------

    @Test
    void preModelQueryKindIsDeclared() {
        // The enum constant is named the same as the doc
        // string ("PRE_MODEL_QUERY") so callers and
        // JSON-RPC logs are consistent.
        assertThat(Hook.Kind.valueOf("PRE_MODEL_QUERY")).isNotNull();
    }

    @Test
    void postStreamEndKindIsDeclared() {
        assertThat(Hook.Kind.valueOf("POST_STREAM_END")).isNotNull();
    }

    @Test
    void allFiveRoundsOfKindsStillPresent() {
        // The original five kinds (PRE_TOOL_USE, POST_TOOL_USE,
        // USER_PROMPT_SUBMIT, STOP, SESSION_IDLE) are still
        // present alongside the two new ones — adding a Kind
        // must NEVER break existing subscribers.
        assertThat(Hook.Kind.values())
                .contains(
                        Hook.Kind.PRE_TOOL_USE,
                        Hook.Kind.POST_TOOL_USE,
                        Hook.Kind.USER_PROMPT_SUBMIT,
                        Hook.Kind.STOP,
                        Hook.Kind.SESSION_IDLE,
                        Hook.Kind.PRE_MODEL_QUERY,
                        Hook.Kind.POST_STREAM_END);
    }

    // -------------------------------------------------------------------
    //  Outcome.Async: deferred verdict
    // -------------------------------------------------------------------

    @Test
    void asyncOutcome_carriesFuture() {
        CompletableFuture<Hook.Outcome> f = new CompletableFuture<>();
        Hook.Outcome.Async a = new Hook.Outcome.Async(f);
        assertThat(a.future()).isSameAs(f);
    }

    @Test
    void asyncOutcome_ofFactoryMatchesConstructor() {
        CompletableFuture<Hook.Outcome> f = new CompletableFuture<>();
        Hook.Outcome.Async viaFactory = Hook.Outcome.Async.of(f);
        assertThat(viaFactory.future()).isSameAs(f);
    }

    @Test
    void asyncOutcome_nullFutureRejected() {
        // A null future would defeat the purpose: the
        // runtime has nothing to wait on. The compact
        // constructor on the record enforces this.
        assertThatThrownBy(() -> new Hook.Outcome.Async(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    void asyncOutcome_isAnOutcome() {
        // A future resolves to another Outcome (e.g. a
        // ContinueWithResult that lands late). The
        // sealed-interface contract means Async is a
        // legitimate Outcome subtype, and the runtime
        // can treat it the same as Continue until the
        // future completes.
        CompletableFuture<Hook.Outcome> f = CompletableFuture.completedFuture(new Hook.Outcome.Continue());
        Hook.Outcome o = new Hook.Outcome.Async(f);
        assertThat(o).isInstanceOf(Hook.Outcome.class);
    }

    // -------------------------------------------------------------------
    //  HookContext.forPreModelQuery
    // -------------------------------------------------------------------

    @Test
    void forPreModelQuery_carriesModelIdAndIteration() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", "you are a helpful assistant"),
                Map.of("role", "user", "content", "hello")
        );
        Hook.HookContext ctx = Hook.HookContext.forPreModelQuery(
                "sess-1", "minimax-text-01", 3, messages);
        // The dedicated fields are populated.
        assertThat(ctx.sessionId()).isEqualTo("sess-1");
        assertThat(ctx.modelId()).isEqualTo("minimax-text-01");
        assertThat(ctx.iteration()).isEqualTo(3);
        // toolInput is also populated for legacy hooks
        // that read the standard fields.
        assertThat(ctx.toolInput()).containsKeys("modelId", "iteration", "messages");
        assertThat(((Number) ctx.toolInput().get("iteration")).intValue()).isEqualTo(3);
        // Tool-name / result / stop fields are null —
        // this is not a tool-use event.
        assertThat(ctx.toolName()).isNull();
        assertThat(ctx.result()).isNull();
        assertThat(ctx.stopReason()).isNull();
    }

    @Test
    void forPreModelQuery_nullMessagesBecomesEmptyList() {
        // The factory must not NPE on a null messages list
        // — the engine sometimes hands us an empty
        // session (e.g. an early bail-out).
        Hook.HookContext ctx = Hook.HookContext.forPreModelQuery(
                "sess-1", "minimax-text-01", 0, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) ctx.toolInput().get("messages");
        assertThat(messages).isNotNull().isEmpty();
    }

    @Test
    void forPreModelQuery_messagesListIsImmutable() {
        // The factory wraps the messages list in
        // List.copyOf so a hook that mutates the returned
        // list cannot corrupt the engine's view of the
        // transcript.
        List<Map<String, Object>> messages = new java.util.ArrayList<>();
        messages.add(Map.of("role", "user", "content", "hi"));
        Hook.HookContext ctx = Hook.HookContext.forPreModelQuery(
                "sess-1", "m", 0, messages);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) ctx.toolInput().get("messages");
        assertThatThrownBy(() -> out.add(Map.of("role", "user", "content", "hacked")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -------------------------------------------------------------------
    //  HookContext.forPostStreamEnd
    // -------------------------------------------------------------------

    @Test
    void forPostStreamEnd_carriesModelTextAndToolCalls() {
        List<Map<String, Object>> toolCalls = List.of(
                Map.of("name", "read_file", "input", Map.of("path", "/a/b.md"))
        );
        Hook.HookContext ctx = Hook.HookContext.forPostStreamEnd(
                "sess-2", "minimax-text-01", 7, "I will read the file.", toolCalls);
        // Dedicated fields are populated.
        assertThat(ctx.sessionId()).isEqualTo("sess-2");
        assertThat(ctx.modelId()).isEqualTo("minimax-text-01");
        assertThat(ctx.iteration()).isEqualTo(7);
        assertThat(ctx.modelText()).isEqualTo("I will read the file.");
        assertThat(ctx.toolCalls()).hasSize(1);
        // Legacy fields carry the same data.
        assertThat(ctx.toolInput()).containsEntry("modelText", "I will read the file.");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legacyCalls = (List<Map<String, Object>>) ctx.toolInput().get("toolCalls");
        assertThat(legacyCalls).hasSize(1);
    }

    @Test
    void forPostStreamEnd_nullToolCallsBecomesEmptyList() {
        Hook.HookContext ctx = Hook.HookContext.forPostStreamEnd(
                "sess-2", "m", 0, "no tools", null);
        assertThat(ctx.toolCalls()).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legacyCalls = (List<Map<String, Object>>) ctx.toolInput().get("toolCalls");
        assertThat(legacyCalls).isEmpty();
    }

    @Test
    void forPostStreamEnd_nullModelTextBecomesEmptyString() {
        // The modelText field is documented as a String
        // (not nullable) so a downstream consumer can
        // safely call .length() / .isEmpty() without a
        // null check. Null is normalised to "".
        Hook.HookContext ctx = Hook.HookContext.forPostStreamEnd(
                "sess-2", "m", 0, null, List.of());
        assertThat(ctx.modelText()).isEmpty();
    }

    // -------------------------------------------------------------------
    //  Existing factories still build the record correctly
    // -------------------------------------------------------------------

    @Test
    void preAndPostAndStopFactoriesStillWork() {
        // The new fields (modelId / iteration / modelText /
        // toolCalls) are nullable for the legacy kinds.
        // Verify the existing factories continue to
        // produce well-formed contexts.
        Hook.HookContext pre = Hook.HookContext.forPre("s", "read_file", Map.of("path", "/a"));
        assertThat(pre.modelId()).isNull();
        assertThat(pre.iteration()).isNull();
        assertThat(pre.modelText()).isNull();
        assertThat(pre.toolCalls()).isNull();
        assertThat(pre.toolName()).isEqualTo("read_file");

        Hook.HookContext post = Hook.HookContext.forPost("s", "read_file", Map.of("path", "/a"), null);
        assertThat(post.modelId()).isNull();

        Hook.HookContext stop = Hook.HookContext.forStop("s");
        assertThat(stop.toolName()).isNull();
        assertThat(stop.modelId()).isNull();

        Hook.HookContext idle = Hook.HookContext.forSessionIdle("s", "end_turn", List.of());
        assertThat(idle.modelId()).isNull();
        assertThat(idle.iteration()).isNull();

        Hook.HookContext ups = Hook.HookContext.forUserPromptSubmit("s", "hello");
        assertThat(ups.modelId()).isNull();
    }
}
