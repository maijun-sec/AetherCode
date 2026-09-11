package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Status bar widget.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.status}. The Python
 * {@code StatusBar(Widget)} renders the model label, effort, working
 * directory, git branch, connection state, token usage, and elapsed
 * time across the bottom of the TUI. The Java port preserves the
 * public data shape and the picker-target metadata contract.</p>
 */
public class StatusBar extends Widget {

    /** Connection states the status bar can display. */
    public enum ConnectionState { NONE, CONNECTING, RECONNECTING, RESUMING }

    /** Clickable span kinds in the model label. */
    public enum PickerTarget { MODEL, EFFORT }

    /** Owners that may write the shared status-message slot. */
    public enum MessageSource { AGENT, HOOKS }

    private String modelProvider;
    private String modelName;
    private String effort;
    private String cwd;
    private String gitBranch;
    private ConnectionState connection;
    private long totalTokens;
    private long costMicrousd;
    private long elapsedSeconds;
    private String statusMessage;
    private MessageSource statusMessageSource;
    private String defaultModel;
    private String defaultEffort;

    public StatusBar() {
        super("", "status-bar");
    }

    public String modelProvider() { return modelProvider; }
    public void setModelProvider(String modelProvider) { this.modelProvider = modelProvider; }

    public String modelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String effort() { return effort; }
    public void setEffort(String effort) { this.effort = effort; }

    public String cwd() { return cwd; }
    public void setCwd(String cwd) { this.cwd = cwd; }

    public String gitBranch() { return gitBranch; }
    public void setGitBranch(String gitBranch) { this.gitBranch = gitBranch; }

    public ConnectionState connection() { return connection; }
    public void setConnection(ConnectionState connection) { this.connection = connection; }

    public long totalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }

    public long costMicrousd() { return costMicrousd; }
    public void setCostMicrousd(long costMicrousd) { this.costMicrousd = costMicrousd; }

    public long elapsedSeconds() { return elapsedSeconds; }
    public void setElapsedSeconds(long elapsedSeconds) { this.elapsedSeconds = elapsedSeconds; }

    public String statusMessage() { return statusMessage; }
    public void setStatusMessage(String msg, MessageSource source) {
        this.statusMessage = msg;
        this.statusMessageSource = source;
    }

    public String defaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public String defaultEffort() { return defaultEffort; }
    public void setDefaultEffort(String defaultEffort) { this.defaultEffort = defaultEffort; }

    @Override
    public WidgetNode render() {
        List<WidgetNode> rows = new ArrayList<>();
        // Model label with picker-target metadata.
        if (modelName != null) {
            String label = modelProvider == null || modelProvider.isEmpty()
                    ? modelName
                    : modelProvider + ":" + modelName;
            String suffix = "";
            if (modelProvider != null && modelProvider.equals(defaultModel)) suffix += " default";
            rows.add(new WidgetNode.Container(WidgetNode.Layout.HORIZONTAL, List.of(
                    new WidgetNode.Static("model: ", WidgetNode.Role.MUTED),
                    new WidgetNode.Static(label + suffix,
                            WidgetNode.Role.PRIMARY, false, true, false))));
        }
        if (effort != null) {
            String suffix = effort.equals(defaultEffort) ? " default" : "";
            rows.add(new WidgetNode.Static("effort: " + effort + suffix,
                    WidgetNode.Role.MUTED));
        }
        if (cwd != null) {
            rows.add(new WidgetNode.Static("cwd: " + WelcomeBanner.homePrefixed(cwd),
                    WidgetNode.Role.MUTED));
        }
        if (gitBranch != null) {
            rows.add(new WidgetNode.Static("branch: " + gitBranch, WidgetNode.Role.MUTED));
        }
        if (connection != null && connection != ConnectionState.NONE) {
            rows.add(new WidgetNode.Static("status: " + connection.name().toLowerCase(),
                    WidgetNode.Role.WARNING));
        }
        rows.add(new WidgetNode.Static("tokens: " + totalTokens, WidgetNode.Role.MUTED));
        rows.add(new WidgetNode.Static(
                "cost: " + Loading.formatDuration(costMicrousd / 1_000_000L),
                WidgetNode.Role.MUTED));
        rows.add(new WidgetNode.Static(
                "elapsed: " + Loading.formatDuration(elapsedSeconds),
                WidgetNode.Role.MUTED));
        if (statusMessage != null) {
            rows.add(new WidgetNode.Static(statusMessage, WidgetNode.Role.MUTED));
        }
        return new WidgetNode.Container(WidgetNode.Layout.HORIZONTAL, rows, "status-bar");
    }

    /** Format a token count compactly (e.g. {@code 12345 -> "12k"}). */
    public static String compactTokens(long count) {
        if (count < 1_000L) return Long.toString(count);
        if (count < 1_000_000L) return String.format("%dk", count / 1_000L);
        return String.format("%.1fM", count / 1_000_000.0);
    }
}
