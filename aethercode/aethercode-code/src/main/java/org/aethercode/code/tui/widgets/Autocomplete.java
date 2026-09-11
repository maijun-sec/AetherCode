package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Autocomplete system for {@code @} mentions and {@code /} commands.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.autocomplete}. The
 * Python module exposes a {@code MultiCompletionManager} that hosts a
 * {@code SlashCommandController} and a {@code FuzzyFileController}. The
 * Java port preserves the public {@code CompletionResult} enum and
 * the {@code CompletionView} / {@code CompletionController} protocols.</p>
 */
public final class Autocomplete {

    private Autocomplete() {}

    /** Result of handling a key event in the completion system. */
    public enum CompletionResult {
        /** Key not handled, let default behavior proceed. */
        IGNORED,
        /** Key handled, prevent default. */
        HANDLED,
        /** Key triggers submission (e.g. Enter on slash command). */
        SUBMIT
    }

    /** A single suggestion row. */
    public record Suggestion(String label, String description) {}

    /** Protocol for views that can display completion suggestions. */
    public interface CompletionView {
        void renderCompletionSuggestions(List<Suggestion> suggestions, int selectedIndex);
        void clearCompletionSuggestions();
        void replaceCompletionRange(int start, int end, String replacement);
    }

    /** A controller that maps a trigger ({@code /} or {@code @}) to a list of suggestions. */
    public interface CompletionController {
        /** Whether this controller can handle the current input state. */
        boolean canHandle(String text, int cursorIndex);
        /** Refresh the suggestion list after an input change. */
        void onTextChanged(String text, int cursorIndex);
        /** Handle a key event and return how it was handled. */
        CompletionResult onKey(String key, String text, int cursorIndex);
        /** Reset the controller's state. */
        void reset();
        /** The current suggestions and selected index. */
        Suggestions current();
    }

    /** A snapshot of the controller's current state. */
    public record Suggestions(List<Suggestion> suggestions, int selectedIndex) {
        public static final Suggestions EMPTY = new Suggestions(List.of(), 0);
    }

    /** Slash-command controller. Hosts inject the {@code CommandEntry} list. */
    public static final class SlashCommandController implements CompletionController {
        public record CommandEntry(String name, String description) {}

        private final List<CommandEntry> commands;
        private Suggestions suggestions = Suggestions.EMPTY;

        public SlashCommandController(List<CommandEntry> commands) {
            this.commands = commands == null ? List.of() : List.copyOf(commands);
        }

        @Override
        public boolean canHandle(String text, int cursorIndex) {
            if (text == null) return false;
            int head = text.lastIndexOf('\n', cursorIndex - 1);
            int start = head < 0 ? 0 : head + 1;
            if (start >= text.length() || text.charAt(start) != '/') return false;
            int space = text.indexOf(' ', start);
            return space < 0 || space >= cursorIndex;
        }

        @Override
        public void onTextChanged(String text, int cursorIndex) {
            if (!canHandle(text, cursorIndex)) {
                suggestions = Suggestions.EMPTY;
                return;
            }
            int head = text.lastIndexOf('\n', cursorIndex - 1);
            int start = head < 0 ? 0 : head + 1;
            String prefix = text.substring(start + 1).toLowerCase();
            List<Suggestion> matches = new ArrayList<>();
            for (CommandEntry c : commands) {
                if (c.name().toLowerCase().startsWith(prefix)) {
                    matches.add(new Suggestion(c.name(), c.description()));
                }
            }
            suggestions = new Suggestions(matches, 0);
        }

        @Override
        public CompletionResult onKey(String key, String text, int cursorIndex) {
            return CompletionResult.IGNORED;
        }

        @Override
        public void reset() { suggestions = Suggestions.EMPTY; }

        @Override
        public Suggestions current() { return suggestions; }
    }

    /** Fuzzy file controller. Hosts inject the file-list supplier. */
    public static final class FuzzyFileController implements CompletionController {
        public interface FileSupplier { List<String> files(); }

        private final FileSupplier supplier;
        private Suggestions suggestions = Suggestions.EMPTY;

        public FuzzyFileController(FileSupplier supplier) {
            this.supplier = supplier == null ? List::of : supplier;
        }

        @Override
        public boolean canHandle(String text, int cursorIndex) {
            if (text == null) return false;
            int head = text.lastIndexOf('\n', cursorIndex - 1);
            int start = head < 0 ? 0 : head + 1;
            return start < text.length() && text.charAt(start) == '@';
        }

        @Override
        public void onTextChanged(String text, int cursorIndex) {
            if (!canHandle(text, cursorIndex)) {
                suggestions = Suggestions.EMPTY;
                return;
            }
            int head = text.lastIndexOf('\n', cursorIndex - 1);
            int start = head < 0 ? 0 : head + 1;
            String prefix = (cursorIndex > start + 1) ? text.substring(start + 1, cursorIndex).toLowerCase() : "";
            List<Suggestion> matches = new ArrayList<>();
            for (String f : supplier.files()) {
                if (f.toLowerCase().contains(prefix)) {
                    matches.add(new Suggestion(f, ""));
                }
            }
            suggestions = new Suggestions(matches, 0);
        }

        @Override
        public CompletionResult onKey(String key, String text, int cursorIndex) {
            return CompletionResult.IGNORED;
        }

        @Override
        public void reset() { suggestions = Suggestions.EMPTY; }

        @Override
        public Suggestions current() { return suggestions; }
    }

    /** Multi-completion manager. Dispatches the active controller. */
    public static final class MultiCompletionManager {
        private final List<CompletionController> controllers = new ArrayList<>();
        private CompletionController active;

        public void add(CompletionController controller) { controllers.add(controller); }

        public void onTextChanged(String text, int cursorIndex) {
            CompletionController next = null;
            for (CompletionController c : controllers) {
                if (c.canHandle(text, cursorIndex)) { next = c; break; }
            }
            if (next != active) {
                if (active != null) active.reset();
                active = next;
            }
            if (active != null) active.onTextChanged(text, cursorIndex);
        }

        public CompletionResult onKey(String key, String text, int cursorIndex) {
            return active == null ? CompletionResult.IGNORED : active.onKey(key, text, cursorIndex);
        }

        public Optional<Suggestions> current() {
            return active == null ? Optional.empty() : Optional.ofNullable(active.current());
        }

        public void reset() {
            if (active != null) active.reset();
            active = null;
        }
    }
}
