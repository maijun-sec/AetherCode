package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Ask-user middleware for interactive question-answering during agent
 * execution.
 *
 * <p>Java-native port of the Python {@code deepagents_code.ask_user} module.
 * The Java port provides the public surface (tool description, system-prompt
 * block, and the {@link AskUserTypes} record shapes) so other parts of the
 * package can depend on the contracts; the middleware base class is
 * {@code deepagents-core}'s {@code AgentMiddleware}, so the full
 * ToolRuntime/injection logic lives in a follow-up port that wires the
 * Java type system to the upstream middleware.</p>
 */
public final class AskUser {
    private AskUser() {}

    private static final Logger LOG = LoggerFactory.getLogger(AskUser.class);

    /** Tool description for {@code ask_user}. */
    public static final String ASK_USER_TOOL_DESCRIPTION =
            "Ask the user one or more questions when you need clarification or input before proceeding.\n"
                    + "\n"
                    + "Each question can be one of:\n"
                    + "- \"text\": Free-form text response from the user\n"
                    + "- \"multiple_choice\": User selects exactly one of the predefined options (an \"Other\" option is always available)\n"
                    + "- \"multi_select\": User selects one or more of the predefined options (an \"Other\" free-form option is always available; filling one reveals an \"Add another\" slot for more custom values)\n"
                    + "\n"
                    + "For \"multiple_choice\" and \"multi_select\" questions, provide a list of choices, each with a non-empty \"value\". For \"multiple_choice\" the user picks one option or types a custom answer via the \"Other\" option; for \"multi_select\" the user toggles one or more of the provided options and may also add one or more custom free-form Other values among the selected values.\n"
                    + "\n"
                    + "A \"multi_select\" answer is returned as a JSON array of the selected values, e.g. [\"a\", \"b\"] (an optional question the user leaves untouched returns []). \"multi_select\" choice values and custom Other text may themselves contain commas, quotes, and newlines. A \"multiple_choice\" value is returned on its own with no escaping, so keep that one to a single line.\n"
                    + "\n"
                    + "By default all questions are required. Set \"required\" to false for optional questions that the user can skip. Do not include \"(required)\", \"(optional)\", \"- optional\", or similar annotations in the question text — the UI renders that separately based on the \"required\" field.\n"
                    + "\n"
                    + "Use this tool when:\n"
                    + "- You need clarification on ambiguous requirements\n"
                    + "- You want the user to choose between multiple valid approaches\n"
                    + "- You need specific information only the user can provide\n"
                    + "- You want to confirm a plan before executing it\n"
                    + "\n"
                    + "Do NOT use this tool for:\n"
                    + "- Simple yes/no confirmations (just proceed with your best judgment)\n"
                    + "- Questions you can answer yourself from context\n"
                    + "- Trivial decisions that don't meaningfully affect the outcome";

    /** System-prompt block that introduces the {@code ask_user} tool. */
    public static final String ASK_USER_SYSTEM_PROMPT =
            "## `ask_user`\n"
                    + "\n"
                    + "You have access to the `ask_user` tool to ask the user questions when you need clarification or input.\n"
                    + "Use this tool sparingly - only when you genuinely need information from the user that you cannot determine from context.\n"
                    + "\n"
                    + "When using `ask_user`:\n"
                    + "- Be concise and specific with your questions\n"
                    + "- Use multiple choice when there are clear options and exactly one applies\n"
                    + "- Use multi-select when the user may legitimately pick several of the options\n"
                    + "- Use text input when you need free-form responses\n"
                    + "- Group related questions into a single ask_user call rather than making multiple calls\n"
                    + "- Never ask questions you can answer yourself from the available context";

    /**
     * One normalized question. Mirrors the Python {@code Question} TypedDict.
     */
    public record Question(
            String type,
            String question,
            List<Map<String, String>> choices,
            Boolean required) {
    }

    /**
     * Authoritative answer payload, one entry per question.
     */
    public record AskUserRequest(
            String type,
            List<Question> questions,
            String toolCallId) {
    }

    /**
     * Authorization receipt attached to a successful answer.
     */
    public record AskUserAuthorizationReceipt(
            int version,
            String threadId,
            String turnId,
            String toolCallId,
            List<String> answers) {
    }

    /**
     * Format a one-line "(error: ...)" answer for a failed prompt.
     */
    public static String formatAskUserErrorAnswer(String detail) {
        return "(error: " + (detail == null ? "ask_user interaction failed" : detail) + ")";
    }

    /**
     * Format a Q&A transcript suitable for the model's {@code ToolMessage}.
     */
    public static String formatAskUserTranscript(List<Question> questions, List<String> answers) {
        if (questions == null || answers == null) return "";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(questions.size(), answers.size());
        for (int i = 0; i < n; i++) {
            Question q = questions.get(i);
            String a = answers.get(i);
            if (i > 0) sb.append("\n");
            sb.append("Q: ").append(q.question() == null ? "" : q.question()).append("\n");
            sb.append("A: ").append(a == null ? "" : a);
        }
        return sb.toString();
    }
}
