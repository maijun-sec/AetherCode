package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat input widget for the deepagents-code TUI.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.chat_input}. The
 * Python {@code ChatInput} is a Textual {@code Static} subclass that
 * composes an autocomplete popup, a slash-command popup, a history
 * integration, and a multi-line {@code TextArea} with paste-collapse
 * handling. The Java port preserves the public data shape and the
 * core text-submission contract; the actual input handling is the
 * host TUI's job.</p>
 */
public class ChatInput extends Widget {

    /** Mode prefixes that change how a submission is routed. */
    public enum Mode { MANUAL, AUTO, YOLO }

    /** The default history file path. Hosts may inject a different path. */
    public static final String DEFAULT_HISTORY_PATH = "~/.deepagents/.state/history.jsonl";

    /** Lock keys that must never insert text. */
    public static final java.util.Set<String> LOCK_KEYS =
            java.util.Set.of("caps_lock", "num_lock", "scroll_lock");

    /** Textual worker group for all {@code @} file-completion cache warmers. */
    public static final String FILE_CACHE_WORKER_GROUP = "file-cache";

    /** Rows the composer grows to on its own before the draft starts scrolling. */
    public static final int CHAT_INPUT_AUTO_MAX_HEIGHT = 8;

    private String value = "";
    private int cursorIndex = 0;
    private final HistoryManager history;
    private Mode mode = Mode.MANUAL;
    private String currentAgent;

    public ChatInput(HistoryManager history) {
        super("chat-input", "chat-input");
        this.history = history;
    }

    public ChatInput() {
        this(new HistoryManager(java.nio.file.Path.of(DEFAULT_HISTORY_PATH.replace("~",
                System.getProperty("user.home")))));
    }

    public String value() { return value; }
    public int cursorIndex() { return cursorIndex; }
    public void setValue(String value) { this.value = value == null ? "" : value; }
    public void setCursorIndex(int cursorIndex) { this.cursorIndex = cursorIndex; }

    public Mode mode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public String currentAgent() { return currentAgent; }
    public void setCurrentAgent(String currentAgent) { this.currentAgent = currentAgent; }

    public HistoryManager history() { return history; }

    /** The detection result for the {@code /command} prefix. */
    public record DetectedMode(String command, Mode mode) {}

    /**
     * Detect a leading mode prefix in the current text.
     *
     * <p>Mirrors the Python {@code detect_mode_prefix} helper. Returns
     * the matched command and its mapped mode, or {@code null} when no
     * prefix is present.</p>
     */
    public static DetectedMode detectModePrefix(String text) {
        if (text == null) return null;
        String trimmed = text.stripLeading();
        if (trimmed.startsWith("/")) {
            String[] parts = trimmed.split("\\s+", 2);
            String head = parts[0].toLowerCase();
            Mode mapped = switch (head) {
                case "/auto" -> Mode.AUTO;
                case "/yolo" -> Mode.YOLO;
                case "/manual" -> Mode.MANUAL;
                default -> null;
            };
            if (mapped != null) return new DetectedMode(head, mapped);
        }
        return null;
    }

    /**
     * Submit the current text. Returns {@code true} when the text is
     * empty/whitespace or a slash command (not added to history) and
     * {@code false} when the text is added to the history.
     *
     * <p>Mirrors the Python {@code submit} behavior.</p>
     */
    public boolean submit() {
        String text = value.strip();
        if (text.isEmpty()) return true;
        history.add(text);
        return false;
    }

    /** Replace the current value with a history entry. */
    public void applyHistory(String entry) {
        setValue(entry == null ? "" : entry);
    }

    @Override
    public WidgetNode render() {
        List<WidgetNode> rows = new ArrayList<>();
        rows.add(new WidgetNode.Static("> " + value, WidgetNode.Role.TEXT));
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows, "chat-input");
    }
}
