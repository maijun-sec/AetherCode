package org.aethercode.code.tui.widgets;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Shared primitives for inline prompts.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets._inline_prompt}. The
 * Python module exposes three primary classes: {@code InlinePromptCompletion}
 * (resolves a result future at most once), {@code InlinePromptTextArea}
 * (TextArea with paste-burst handling), and {@code InlinePromptOption}
 * (a selectable option row). The Java port mirrors the same public surface
 * with {@link CompletableFuture} in place of {@code asyncio.Future}.</p>
 */
public final class InlinePrompt {

    private InlinePrompt() {}

    /**
     * Leading clause of the toast shown when media is dropped on an inline
     * prompt. Public so tests can assert on the toast without duplicating
     * the whole message.
     */
    public static final String MEDIA_UNSUPPORTED_TOAST_PREFIX = "Only text is supported here";

    /**
     * Resolve an inline prompt result at most once.
     *
     * <p>{@link #setFuture(CompletableFuture)} and {@link #resolve(Object)}
     * may be called in either order: a result recorded before the future
     * is wired is delivered as soon as the future arrives.</p>
     */
    public static final class Completion<ResultT> {
        private CompletableFuture<ResultT> future;
        private boolean resolved;
        private ResultT result;
        private boolean hasResult;

        public boolean resolved() { return resolved; }

        public void setFuture(CompletableFuture<ResultT> future) {
            this.future = future;
            if (resolved && hasResult && !future.isDone()) {
                future.complete(result);
            }
        }

        public boolean resolve(ResultT result) {
            if (resolved) return false;
            this.resolved = true;
            this.result = result;
            this.hasResult = true;
            if (future != null && !future.isDone()) future.complete(result);
            return true;
        }
    }

    /** Return the newline-shortcut hint fragment (e.g. 'Ctrl+J newline'). */
    public static String newlineHint() {
        return "Ctrl+J newline";
    }

    /**
     * Use the ASCII border variant when the active terminal requires it.
     * The Java port is a no-op marker; the host TUI applies the actual
     * border style.
     */
    public static void applyInlinePromptBorder(Widget widget) {
        // No-op: hosts translate to their own border API.
    }

    /**
     * Keep blur from being interpreted as prompt dismissal. The Java port
     * is a marker; hosts are responsible for stopping the underlying
     * event in their event loop.
     */
    public static void stopInlinePromptBlur() {
        // No-op: hosts stop their own events.
    }

    /**
     * A selectable inline-prompt option with a cursor gutter.
     *
     * <p>The Java port is a data class; the host TUI handles the actual
     * selection rendering and input handling.</p>
     */
    public static final class Option extends Widget {
        private final int index;
        private final String text;
        private final String selectedClass;
        private boolean cursorVisible;
        private boolean highlighted;

        public Option(String text, int index, boolean selected, String selectedClass) {
            super("", "inline-prompt-option" + (selected ? " " + (selectedClass == null
                    ? "inline-prompt-option-selected" : selectedClass) : ""));
            this.text = text;
            this.index = index;
            this.cursorVisible = selected;
            this.highlighted = selected;
            this.selectedClass = selectedClass;
        }

        public int optionIndex() { return index; }
        public String text() { return text; }
        public boolean selected() { return cursorVisible; }
        public boolean highlighted() { return highlighted; }

        public void select() { setState(true, true); }
        public void deselect() { setState(false, false); }

        public void setState(boolean cursor, boolean highlighted) {
            this.cursorVisible = cursor;
            this.highlighted = highlighted;
        }

        @Override
        public WidgetNode render() {
            String marker = cursorVisible ? "▶" : " ";
            return new WidgetNode.Container(WidgetNode.Layout.HORIZONTAL,
                    List.of(
                            new WidgetNode.Static(marker + " ", WidgetNode.Role.MUTED),
                            new WidgetNode.Static(text, highlighted ? WidgetNode.Role.PRIMARY : WidgetNode.Role.TEXT)),
                    classes());
        }
    }

    /** Placeholder for {@code InlinePromptTextArea}. Hosts plug in their
     * own TextArea implementation. */
    public static class TextAreaPlaceholder extends Widget {
        private final String submittedValue;
        public TextAreaPlaceholder(String id, String classes) {
            super(id, classes == null ? "inline-prompt-input" : "inline-prompt-input " + classes);
            this.submittedValue = "";
        }
        public String submittedValue() { return submittedValue; }
        public void resetPasteState() { /* no-op */ }

        @Override
        public WidgetNode render() {
            return new WidgetNode.Input(id(), classes(), submittedValue, false);
        }
    }
}
