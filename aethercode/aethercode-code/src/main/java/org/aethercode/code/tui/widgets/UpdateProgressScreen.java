package org.aethercode.code.tui.widgets;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Progress modal for app self-update installs.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.update_progress}. The
 * Python {@code UpdateProgressScreen} is a {@code ModalScreen[None]} that
 * displays self-update progress and a bounded log tail. The Java port
 * preserves the public {@code append_line}, {@code mark_success},
 * {@code mark_failure}, {@code mark_warning} hooks and the
 * {@code tail_limit} cap.</p>
 */
public class UpdateProgressScreen extends ModalScreen<Void> {

    private final String latest;
    private final String command;
    private final Path logPath;
    private final int tailLimit;
    private final Deque<String> tail;
    private boolean detailsVisible;
    private boolean done;
    private String status;
    private String doneGlyph;
    private String copyText;
    private String copyLabel = "log path";

    public UpdateProgressScreen(String latest, String command, Path logPath, int tailLimit) {
        super("", "update-progress-screen");
        this.latest = latest;
        this.command = command;
        this.logPath = logPath;
        this.tailLimit = tailLimit <= 0 ? 30 : tailLimit;
        this.tail = new ArrayDeque<>(this.tailLimit);
        this.status = "Installing v" + latest + "...";
    }

    public String latest() { return latest; }
    public String command() { return command; }
    public Path logPath() { return logPath; }
    public int tailLimit() { return tailLimit; }
    public boolean detailsVisible() { return detailsVisible; }
    public boolean done() { return done; }
    public String status() { return status; }

    /** Append a command output line to the in-memory tail. */
    public void appendLine(String line) {
        if (tail.size() >= tailLimit) tail.pollFirst();
        tail.offerLast(line);
    }

    /** Render the completed-success state. */
    public void markSuccess() {
        this.done = true;
        this.doneGlyph = "✓";
        this.status = "Update complete. Quit and relaunch dcode to use v" + latest + ".";
    }

    /** Render the completed-failure state. */
    public void markFailure(String command) {
        this.done = true;
        this.doneGlyph = "✗";
        this.status = "Update failed. Try manually: " + command;
        this.detailsVisible = true;
    }

    /** Render a completed state that needs user action. */
    public void markWarning(String warning, String copyText, String copyLabel) {
        this.done = true;
        this.doneGlyph = "⚠";
        this.status = warning;
        this.copyText = copyText;
        this.copyLabel = copyLabel == null ? "fix command" : copyLabel;
        this.detailsVisible = true;
    }

    public void actionToggleDetails() {
        this.detailsVisible = !this.detailsVisible;
    }

    public void actionCancel() {
        if (done) dismiss(null);
    }

    public void actionQuitApp() {
        if (done) System.exit(0);
    }

    /** Copy warning action text or the persisted log path. */
    public void actionCopyLogPath() {
        String text = copyText;
        if (text == null) {
            if (!detailsVisible) return;
            text = logPath.toString();
            copyLabel = "log path";
        }
        if (text == null || text.isEmpty()) return;
        // The host TUI is responsible for the actual clipboard call. The Java
        // port surfaces the resolved text and label so the host can call
        // its own clipboard API and post the success toast.
    }

    public String copyText() { return copyText; }
    public String copyLabel() { return copyLabel; }

    /** Build the footer help text. */
    public String helpText() {
        StringBuilder sb = new StringBuilder();
        sb.append("d ").append(detailsVisible ? "Hide details" : "Show details");
        sb.append(" · Esc ").append(done ? "close" : "close when complete");
        if (copyText != null) {
            sb.append(" · c copy ").append(copyLabel);
        } else if (detailsVisible) {
            sb.append(" · c copy log path");
        }
        if (done) sb.append(" · q quit");
        return sb.toString();
    }

    @Override
    public WidgetNode render() {
        WidgetNode title = new WidgetNode.Static("Updating dcode",
                WidgetNode.Role.PRIMARY, false, true, false);
        WidgetNode statusRow = new WidgetNode.Container(WidgetNode.Layout.HORIZONTAL,
                List.of(
                        new WidgetNode.Static((doneGlyph == null ? "⠋" : doneGlyph) + " ",
                                WidgetNode.Role.PRIMARY),
                        new WidgetNode.Static(status, WidgetNode.Role.TEXT)),
                "up-status-row");
        List<WidgetNode> children = new java.util.ArrayList<>();
        children.add(title);
        children.add(statusRow);
        if (detailsVisible) {
            children.add(new WidgetNode.Static("Running command: " + command,
                    WidgetNode.Role.MUTED));
            children.add(new WidgetNode.Log(new java.util.ArrayList<>(tail),
                    tailLimit, true));
            children.add(new WidgetNode.Static("Log: " + logPath, WidgetNode.Role.MUTED));
        }
        children.add(new WidgetNode.Static(helpText(),
                WidgetNode.Role.MUTED, true, false, true));
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, children,
                "update-progress-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.of("d", "toggle_details", "Details"),
                KeyBinding.of("c", "copy_log_path", "Copy log path"),
                KeyBinding.of("q", "quit_app", "Quit"),
                KeyBinding.of("escape", "cancel", "Close"));
    }
}
