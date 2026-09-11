package org.aethercode.code.tui.widgets;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Goal acceptance-criteria review widget.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.goal_review}. The
 * Python module exposes a {@code GoalReviewMenu(Container)} with four
 * options (accept, edit, reject with message, cancel) plus a nested
 * {@code GoalReviewTextArea} for inline editing.</p>
 *
 * <p>The Java port preserves the public {@code set_future} /
 * {@code Decided} contract and the option tuple.</p>
 */
public final class GoalReview {

    private GoalReview() {}

    /** (label, action-name) tuples in display order. */
    public static final List<OptionTuple> OPTIONS = List.of(
            new OptionTuple("1. Accept proposed criteria (y)", "accept"),
            new OptionTuple("2. Edit criteria (e)", "edit"),
            new OptionTuple("3. Reject with message (r)", "reject_with_message"),
            new OptionTuple("4. Cancel (n)", "cancel")
    );

    public record OptionTuple(String label, String action) {}

    /** Widget result when the generated criteria are accepted unchanged. */
    public record Accepted(String type) implements Result {
        public Accepted() { this("accepted"); }
    }

    /** Widget result when the user submits revised criteria. */
    public record Edited(String type, String criteria) implements Result {
        public Edited(String criteria) { this("edited", criteria); }
    }

    /** Widget result when the user rejects criteria with feedback. */
    public record Rejected(String type, String message) implements Result {
        public Rejected(String message) { this("rejected", message); }
    }

    /** Widget result when the user cancels the proposal. */
    public record Cancelled(String type) implements Result {
        public Cancelled() { this("cancelled"); }
    }

    /** Sum of the four result types. */
    public sealed interface Result permits Accepted, Edited, Rejected, Cancelled {}

    /**
     * Inline review widget for generated goal acceptance criteria.
     *
     * <p>The Java port preserves the constructor and the {@code Decided}
     * dispatch contract. The actual keyboard handling is the host TUI's
     * job; the host calls {@link #actionAccept()},
     * {@link #actionEdit()}, {@link #actionRejectWithMessage()}, or
     * {@link #actionCancel()} in response to its own bindings.</p>
     */
    public static final class Menu extends Widget {
        private final String objective;
        private final String criteria;
        private final boolean amendment;
        private int selected = 0;
        private String inputMode;       // "edit" | "reject" | null
        private final InlinePrompt.Completion<Result> completion = new InlinePrompt.Completion<>();
        private final List<InlinePrompt.Option> optionWidgets = new java.util.ArrayList<>();

        public Menu(String objective, String criteria, boolean amendment, String id) {
            super(id == null ? "goal-review-menu" : id,
                    "inline-prompt goal-review-menu");
            this.objective = objective;
            this.criteria = criteria;
            this.amendment = amendment;
            for (int i = 0; i < OPTIONS.size(); i++) {
                optionWidgets.add(new InlinePrompt.Option(OPTIONS.get(i).label(),
                        i, i == selected, "goal-review-option-selected"));
            }
        }

        public String objective() { return objective; }
        public String criteria() { return criteria; }
        public boolean amendment() { return amendment; }
        public int selected() { return selected; }
        public String inputMode() { return inputMode; }
        public InlinePrompt.Completion<Result> completion() { return completion; }

        public void setFuture(CompletableFuture<Result> future) {
            completion.setFuture(future);
        }

        @Override
        public WidgetNode render() {
            String title = amendment ? "Review goal amendment" : "Review goal criteria";
            List<WidgetNode> children = new java.util.ArrayList<>();
            children.add(new WidgetNode.Static("▶ " + title,
                    WidgetNode.Role.PRIMARY, false, true, false));
            String source = amendment
                    ? "**Proposed objective**\n\n" + objective
                            + "\n\n**Proposed criteria**\n\n" + criteria
                    : "**Proposed criteria**\n\n" + criteria;
            children.add(new WidgetNode.Markdown(source, "goal-review-markdown"));
            children.add(new WidgetNode.Container(WidgetNode.Layout.VERTICAL, optionWidgets.stream()
                    .map(InlinePrompt.Option::render)
                    .toList()));
            children.add(new WidgetNode.Static("", WidgetNode.Role.MUTED));
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, children,
                    "goal-review-menu");
        }

        public void actionMoveUp() {
            if (inputMode != null) return;
            selected = (selected - 1 + OPTIONS.size()) % OPTIONS.size();
            updateOptions();
        }

        public void actionMoveDown() {
            if (inputMode != null) return;
            selected = (selected + 1) % OPTIONS.size();
            updateOptions();
        }

        public void actionSelect() {
            if (inputMode != null) return;
            String actionName = OPTIONS.get(selected).action();
            switch (actionName) {
                case "accept" -> actionAccept();
                case "edit" -> actionEdit();
                case "reject_with_message" -> actionRejectWithMessage();
                case "cancel" -> actionCancel();
            }
        }

        public void actionAccept() {
            if (inputMode != null) return;
            submit(new Accepted());
        }

        public void actionEdit() {
            if (completion.resolved() || inputMode != null) return;
            inputMode = "edit";
            updateOptions();
        }

        public void actionRejectWithMessage() {
            if (completion.resolved() || inputMode != null) return;
            inputMode = "reject";
            updateOptions();
        }

        public void actionCancel() {
            if (completion.resolved()) return;
            if (inputMode != null) {
                inputMode = null;
                updateOptions();
                return;
            }
            submit(new Cancelled());
        }

        public void submitEdit(String text) {
            String trimmed = text == null ? "" : text.strip();
            if (trimmed.isEmpty()) return;
            submit(new Edited(trimmed));
        }

        public void submitRejection(String text) {
            String trimmed = text == null ? "" : text.strip();
            if (trimmed.isEmpty()) return;
            submit(new Rejected(trimmed));
        }

        private void submit(Result result) {
            if (completion.resolved()) return;
            completion.resolve(result);
        }

        private void updateOptions() {
            for (int i = 0; i < optionWidgets.size(); i++) {
                optionWidgets.get(i).setState(
                        i == selected,
                        i == selected && inputMode == null);
            }
        }
    }
}
