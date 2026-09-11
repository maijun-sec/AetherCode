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

        long deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS;
        while (ask.answer == null && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        if (ask.answer == null) {
            return Tool.ToolResult.error("user did not answer within " + (DEFAULT_TIMEOUT_MS/1000) + "s");
        }
        return Tool.ToolResult.of(ask.answer);
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
