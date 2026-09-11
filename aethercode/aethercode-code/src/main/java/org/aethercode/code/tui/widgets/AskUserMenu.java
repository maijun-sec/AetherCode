package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Ask-user widget for interactive questions during agent execution.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.ask_user}. The Python
 * module exposes {@code AskUserMenu(Container)} that renders a list of
 * {@code Question}s (single-choice, multi-choice, free-text) and
 * returns a {@code AskUserWidgetResult} on submit. The Java port
 * preserves the public data shape and dismiss contract.</p>
 */
public final class AskUserMenu {

    private AskUserMenu() {}

    /** Question types. */
    public enum QuestionType { SINGLE_CHOICE, MULTI_CHOICE, FREE_TEXT }

    /** Type unions used by the Python {@code CHOICE_QUESTION_TYPES} and {@code QUESTION_TYPES}. */
    public static final java.util.Set<QuestionType> CHOICE_QUESTION_TYPES =
            java.util.Set.of(QuestionType.SINGLE_CHOICE, QuestionType.MULTI_CHOICE);
    public static final java.util.Set<QuestionType> QUESTION_TYPES =
            java.util.Set.of(QuestionType.values());

    /** A single choice option. */
    public record Choice(String value, String label) {}

    /** A question. */
    public record Question(
            String id,
            String prompt,
            QuestionType type,
            List<Choice> choices,
            boolean required,
            String placeholder) {}

    /** Result of one question. */
    public record QuestionResult(String id, List<String> selected, String freeText) {}

    /** Aggregated result of a submission. */
    public record WidgetResult(List<QuestionResult> answers, boolean submitted) {}

    /** Other-choice label rendered in choice lists. */
    public static final String OTHER_CHOICE_LABEL = "Other (type your answer)";

    /** Add-another-other label. */
    public static final String ADD_ANOTHER_OTHER_LABEL = "Add another custom answer";

    /** Cap on multi-select "other" entries. */
    public static final int MAX_MULTI_SELECT_OTHER_ENTRIES = 10;

    /** Toast shown when a question is missing an answer. */
    public static final String MISSING_ANSWER_TOAST =
            "Please provide an answer to all questions before continuing.";

    /** Toast shown when an "other" is missing free text. */
    public static final String MISSING_OTHER_TEXT_TOAST =
            "Please type a custom answer for Other, or uncheck it.";

    /** Toast shown when the "other" cap is reached. */
    public static final String MAX_OTHER_ENTRIES_TOAST =
            "At most " + MAX_MULTI_SELECT_OTHER_ENTRIES
                    + " custom answers can be added to one "
                    + "question. Combine values into an existing field to add more.";

    /** Toast shown when an internal UI error prevents submission. */
    public static final String UNSUBMITTABLE_ANSWER_TOAST =
            "Could not submit this answer (internal UI error). Press Esc to cancel and retry.";

    /** Whether the user's answer list is empty (used for "required" check). */
    public static boolean answerIsEmpty(WidgetResult result) {
        if (result == null) return true;
        for (QuestionResult a : result.answers()) {
            if (a.selected() != null && !a.selected().isEmpty()) return false;
            if (a.freeText() != null && !a.freeText().isEmpty()) return false;
        }
        return true;
    }

    /** Encode a multi-select answer as a comma-joined string. */
    public static String encodeMultiSelectAnswer(List<String> selected) {
        if (selected == null) return "";
        return String.join(",", selected);
    }

    /** Strip LLM-appended trailing annotations from question text. */
    public static String stripTrailingAnnotation(String text) {
        if (text == null) return "";
        return text.strip().replaceAll(
                "\\s*[-–—]?\\s*(?:optional|required)[.!?]?\\s*$",
                "");
    }

    /**
     * Ask-user menu widget.
     *
     * <p>The Java port preserves the constructor and the
     * {@code submit}/{@code cancel} contract. The actual question
     * rendering and option-list interaction is the host TUI's job.</p>
     */
    public static final class Menu extends Widget {
        private final List<Question> questions;
        private final InlinePrompt.Completion<WidgetResult> completion =
                new InlinePrompt.Completion<>();

        public Menu(List<Question> questions) {
            super("", "ask-user-menu");
            this.questions = questions == null ? List.of() : List.copyOf(questions);
        }

        public List<Question> questions() { return questions; }
        public InlinePrompt.Completion<WidgetResult> completion() { return completion; }

        public void setFuture(CompletableFuture<WidgetResult> future) {
            completion.setFuture(future);
        }

        public void actionSubmit() {
            // Hosts compose a WidgetResult from the current input state and
            // call completion.resolve(...). The Java port exposes the
            // completion so the host's submit handler can drive it.
        }

        public void actionCancel() {
            if (completion.resolved()) return;
            completion.resolve(new WidgetResult(List.of(), false));
        }

        @Override
        public WidgetNode render() {
            List<WidgetNode> rows = new ArrayList<>();
            for (Question q : questions) {
                String header = q.required() ? "* " + q.prompt() : q.prompt();
                rows.add(new WidgetNode.Static(header,
                        q.required() ? WidgetNode.Role.PRIMARY : WidgetNode.Role.TEXT,
                        false, true, false));
                if (CHOICE_QUESTION_TYPES.contains(q.type())) {
                    List<WidgetNode.Option> options = new ArrayList<>();
                    for (Choice c : q.choices()) {
                        options.add(new WidgetNode.Option(c.value(), c.label(), "", false, false));
                    }
                    options.add(new WidgetNode.Option("__other__", OTHER_CHOICE_LABEL, "", false, false));
                    rows.add(new WidgetNode.OptionList(options, "ask-user-" + q.id()));
                } else {
                    rows.add(new WidgetNode.Input("ask-user-input-" + q.id(),
                            q.placeholder() == null ? "Type your answer…" : q.placeholder()));
                }
            }
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                    "ask-user-menu");
        }
    }
}
