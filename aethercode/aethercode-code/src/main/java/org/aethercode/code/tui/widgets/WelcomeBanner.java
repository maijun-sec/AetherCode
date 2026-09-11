package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Welcome banner widget.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.welcome}. The Python
 * {@code WelcomeBanner(Static)} renders a bordered box with the product
 * title, version, and a series of {@code label: value} rows for the
 * active model, working directory, LangSmith tracing, MCP tool count,
 * and any active warnings. The Java port preserves the public
 * {@code update_*} hooks and the row layout.</p>
 */
public class WelcomeBanner extends Widget {

    /** Theme names whose palette is determined by the terminal emulator. */
    public static final java.util.Set<String> ANSI_THEMES =
            java.util.Set.of("ansi-dark", "ansi-light");

    /** UTM source tag appended to LangSmith project URLs. */
    public static final String LANGSMITH_UTM_SOURCE = "deepagents-code";

    /** Label used to word the toast shown after copying the thread ID. */
    public static final String THREAD_COPY_LABEL = "Thread ID";

    private String modelProvider;
    private String modelName;
    private String cwd;
    private String version;
    private String threadId;
    private String projectName;
    private String replicaProject;
    private final Map<String, String> projectUrls = new java.util.HashMap<>();
    private int mcpToolCount;
    private int mcpUnauthenticated;
    private int mcpErrored;
    private int mcpAwaitingReconnect;
    private boolean showModel;
    private boolean showCwd;
    private boolean hideCwd;
    private boolean hideVersion;
    private boolean hideLangsmithTracing;
    private boolean showThreadId;
    private boolean debugEnabled;
    private boolean experimentalEnabled;
    private boolean editableInstall;

    public WelcomeBanner(String threadId, int mcpToolCount, String modelProvider,
                         String modelName, String cwd, int mcpUnauthenticated,
                         int mcpErrored, int mcpAwaitingReconnect) {
        super("", "welcome-banner");
        this.threadId = threadId;
        this.mcpToolCount = mcpToolCount;
        this.modelProvider = modelProvider == null ? "" : modelProvider;
        this.modelName = modelName == null ? "" : modelName;
        this.cwd = cwd == null ? System.getProperty("user.dir") : cwd;
        this.mcpUnauthenticated = mcpUnauthenticated;
        this.mcpErrored = mcpErrored;
        this.mcpAwaitingReconnect = mcpAwaitingReconnect;
        this.version = "0.0.0";   // host injects via updateVersion
        this.showModel = envTruthy("DEEPAGENTS_CODE_SPLASH_SHOW_MODEL");
        this.showCwd = envTruthy("DEEPAGENTS_CODE_SPLASH_SHOW_CWD");
        this.hideCwd = envTruthy("DEEPAGENTS_CODE_HIDE_CWD");
        this.hideVersion = envTruthy("DEEPAGENTS_CODE_HIDE_SPLASH_VERSION");
        this.hideLangsmithTracing = envTruthy("DEEPAGENTS_CODE_HIDE_LANGSMITH_TRACING");
        this.debugEnabled = envTruthy("DEEPAGENTS_CODE_DEBUG");
        this.experimentalEnabled = envTruthy("DEEPAGENTS_CODE_EXPERIMENTAL");
        this.showThreadId = debugEnabled;
        this.editableInstall = false;
    }

    private static boolean envTruthy(String name) {
        String v = System.getenv(name);
        if (v == null) return false;
        v = v.toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    // -- Update hooks --------------------------------------------------------

    public void updateModel(String provider, String model) {
        this.modelProvider = provider == null ? "" : provider;
        this.modelName = model == null ? "" : model;
    }
    public void updateCwd(String cwd) { this.cwd = cwd; }
    public void updateThreadId(String threadId) { this.threadId = threadId; }
    public void updateVersion(String version) { this.version = version; }

    public void setMcpToolCount(int count) { this.mcpToolCount = count; }
    public void setMcpUnauthenticated(int count) { this.mcpUnauthenticated = count; }
    public void setMcpErrored(int count) { this.mcpErrored = count; }
    public void setMcpAwaitingReconnect(int count) { this.mcpAwaitingReconnect = count; }

    public void setConnected(int mcpToolCount, int mcpUnauthenticated,
                             int mcpErrored, int mcpAwaitingReconnect) {
        this.mcpToolCount = mcpToolCount;
        this.mcpUnauthenticated = mcpUnauthenticated;
        this.mcpErrored = mcpErrored;
        this.mcpAwaitingReconnect = mcpAwaitingReconnect;
    }

    public void setProjectUrl(String project, String url) {
        projectUrls.put(project, url);
    }

    public void setEditableInstall(boolean editable) { this.editableInstall = editable; }

    public String modelProvider() { return modelProvider; }
    public String modelName() { return modelName; }
    public String cwd() { return cwd; }
    public String threadId() { return threadId; }
    public String version() { return version; }
    public int mcpToolCount() { return mcpToolCount; }

    // -- Render --------------------------------------------------------------

    @Override
    public WidgetNode render() {
        List<WidgetNode> rows = new ArrayList<>();

        // Title row: "dcode v0.7.8 (debug enabled) (experimental) (local)"
        StringBuilder title = new StringBuilder("▶ dcode");
        if (!hideVersion) title.append("  v").append(version);
        if (debugEnabled) title.append(" (debug enabled)");
        if (experimentalEnabled) title.append(" (experimental)");
        if (!hideVersion && editableInstall) title.append(" (local)");
        rows.add(new WidgetNode.Static(title.toString(), WidgetNode.Role.PRIMARY, false, true, false));

        if (showModel && !modelName.isEmpty()) {
            String value = modelProvider.isEmpty() ? modelName : modelProvider + ":" + modelName;
            rows.add(new WidgetNode.Row("model:    ", new WidgetNode.Static(value, WidgetNode.Role.PRIMARY)));
        }
        if (showCwd && cwd != null && !cwd.isEmpty()) {
            rows.add(new WidgetNode.Row("directory:", new WidgetNode.Static(homePrefixed(cwd), WidgetNode.Role.PRIMARY)));
        }
        if (projectName != null && !projectName.isEmpty()) {
            String url = projectUrls.get(projectName);
            WidgetNode value = url == null
                    ? new WidgetNode.Static("'" + projectName + "'", WidgetNode.Role.PRIMARY)
                    : new WidgetNode.Link("'" + projectName + "'", langsmithLink(url), WidgetNode.Role.PRIMARY);
            rows.add(new WidgetNode.Row("tracing:  ", value));
        }
        if (replicaProject != null && !replicaProject.isEmpty()) {
            String url = projectUrls.get(replicaProject);
            WidgetNode value = url == null
                    ? new WidgetNode.Static("'" + replicaProject + "'", WidgetNode.Role.PRIMARY)
                    : new WidgetNode.Link("'" + replicaProject + "'", langsmithLink(url), WidgetNode.Role.PRIMARY);
            rows.add(new WidgetNode.Row("replica:  ", value));
        }
        if (showThreadId && threadId != null && !threadId.isEmpty()) {
            WidgetNode copy = CopySpans.span(threadId, THREAD_COPY_LABEL);
            WidgetNode container = new WidgetNode.Container(WidgetNode.Layout.HORIZONTAL,
                    List.of(
                            new WidgetNode.Static("thread:   ", WidgetNode.Role.MUTED, true, false, false),
                            copy,
                            new WidgetNode.Static(" (open in langsmith)", WidgetNode.Role.MUTED, true, false, false)),
                    "welcome-thread-row");
            rows.add(container);
        }
        if (mcpToolCount > 0) {
            rows.add(new WidgetNode.Row("mcp tools:",
                    new WidgetNode.Static(mcpToolCount + " loaded", WidgetNode.Role.PRIMARY)));
        }
        if (mcpUnauthenticated > 0) {
            rows.add(new WidgetNode.Row("mcp login:",
                    new WidgetNode.Static(mcpUnauthenticated + " awaiting login", WidgetNode.Role.WARNING)));
        }
        if (mcpErrored > 0) {
            rows.add(new WidgetNode.Row("mcp errors:",
                    new WidgetNode.Static(mcpErrored + " failed to load", WidgetNode.Role.ERROR)));
        }
        if (mcpAwaitingReconnect > 0) {
            rows.add(new WidgetNode.Row("mcp reconnect:",
                    new WidgetNode.Static(mcpAwaitingReconnect + " awaiting reconnect", WidgetNode.Role.MUTED)));
        }
        if (editableInstall && !hideCwd) {
            rows.add(new WidgetNode.Static("(running from editable install)", WidgetNode.Role.MUTED, true, false, true));
        }
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows, "welcome-banner");
    }

    public static String langsmithLink(String url) {
        if (url == null) return null;
        return url + (url.contains("?") ? "&" : "?") + "utm_source=" + LANGSMITH_UTM_SOURCE;
    }

    /** Format a directory path, using {@code ~} for the home directory when possible. */
    public static String homePrefixed(String cwd) {
        if (cwd == null) return "";
        String home = System.getProperty("user.home");
        if (home == null) return cwd;
        if (cwd.equals(home)) return "~";
        if (cwd.startsWith(home + "/") || cwd.startsWith(home + "\\")) {
            return "~/" + cwd.substring(home.length() + 1);
        }
        return cwd;
    }

    public Optional<String> projectUrlFor(String project) {
        return Optional.ofNullable(projectUrls.get(project));
    }
}
