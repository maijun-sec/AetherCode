package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only MCP server and tool viewer modal.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.mcp_viewer}. The
 * Python {@code MCPViewerScreen} is a {@code ModalScreen} listing every
 * connected MCP server and its tools, with reconnect and sign-in
 * affordances per server. The Java port preserves the public data
 * shapes and the dismissal contract.</p>
 */
public class McpViewerScreen extends ModalScreen<McpViewerScreen.DismissValue> {

    /** Server status values. */
    public enum ServerStatus { OK, UNAUTHENTICATED, AWAITING_RECONNECT, ERROR, DISABLED }

    /** Public server information. The host injects the full data shape. */
    public record ServerInfo(String name, ServerStatus status, List<ToolInfo> tools) {}

    /** Public tool information. */
    public record ToolInfo(String name, String description) {}

    /** Sentinel returned by {@link #dismiss(Object)} to request a reconnect. */
    public static final String RECONNECT_REQUEST = "\u0000__mcp_reconnect__";

    /** Textual {@code Binding} key for the in-viewer reconnect action. */
    public static final String RECONNECT_KEY = "ctrl+r";

    /** Display label for {@link #RECONNECT_KEY}. */
    public static final String RECONNECT_KEY_LABEL = "Ctrl+R";

    /** The dismissal value: a server name, a special {@link #RECONNECT_REQUEST}
     *  sentinel, or {@code null} for cancel. */
    public sealed interface DismissValue
            permits DismissValue.Server, DismissValue.Reconnect, DismissValue.Cancel {
        record Server(String name) implements DismissValue {}
        record Reconnect() implements DismissValue {
            public static final Reconnect INSTANCE = new Reconnect();
        }
        record Cancel() implements DismissValue {
            public static final Cancel INSTANCE = new Cancel();
        }
    }

    private final List<ServerInfo> servers;
    private int selected = 0;

    public McpViewerScreen(List<ServerInfo> servers) {
        super("", "mcp-viewer-screen");
        this.servers = servers == null ? List.of() : List.copyOf(servers);
    }

    public List<ServerInfo> servers() { return servers; }
    public int selected() { return selected; }

    @Override
    public WidgetNode render() {
        List<WidgetNode> rows = new ArrayList<>();
        rows.add(new WidgetNode.Static("MCP Servers",
                WidgetNode.Role.PRIMARY, false, true, false));
        for (int i = 0; i < servers.size(); i++) {
            ServerInfo s = servers.get(i);
            String prefix = i == selected ? "▶ " : "  ";
            String glyph = statusGlyph(s.status());
            rows.add(new WidgetNode.Static(
                    prefix + glyph + " " + s.name() + "  (" + s.status() + ")",
                    statusColor(s.status())));
            for (ToolInfo t : s.tools()) {
                rows.add(new WidgetNode.Static("    - " + t.name(),
                        WidgetNode.Role.MUTED, true, false, false));
            }
        }
        rows.add(new WidgetNode.Static(
                "↑/↓ navigate · Enter sign in · " + RECONNECT_KEY_LABEL + " reconnect · Esc close",
                WidgetNode.Role.MUTED, true, false, true));
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                "mcp-viewer-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.of("escape", "cancel", "Close"),
                KeyBinding.priority("up", "move_up", "Up"),
                KeyBinding.priority("k", "move_up", "Up"),
                KeyBinding.priority("down", "move_down", "Down"),
                KeyBinding.priority("j", "move_down", "Down"),
                KeyBinding.priority("enter", "select", "Select"),
                KeyBinding.priority(RECONNECT_KEY, "reconnect", "Reconnect"));
    }

    public void actionCancel() { dismiss(DismissValue.Cancel.INSTANCE); }
    public void actionMoveUp() {
        if (servers.isEmpty()) return;
        selected = (selected - 1 + servers.size()) % servers.size();
    }
    public void actionMoveDown() {
        if (servers.isEmpty()) return;
        selected = (selected + 1) % servers.size();
    }
    public void actionSelect() {
        if (servers.isEmpty()) { dismiss(DismissValue.Cancel.INSTANCE); return; }
        dismiss(new DismissValue.Server(servers.get(selected).name()));
    }
    public void actionReconnect() { dismiss(DismissValue.Reconnect.INSTANCE); }

    /** Map a server status onto a glyph character. */
    public static String statusGlyph(ServerStatus status) {
        return switch (status) {
            case OK -> "✓";
            case UNAUTHENTICATED -> "⚠";
            case AWAITING_RECONNECT -> "○";
            case DISABLED -> "⏸";
            case ERROR -> "✗";
        };
    }

    /** Map a server status onto a theme role. */
    public static WidgetNode.Role statusColor(ServerStatus status) {
        return switch (status) {
            case OK -> WidgetNode.Role.SUCCESS;
            case UNAUTHENTICATED -> WidgetNode.Role.WARNING;
            case ERROR -> WidgetNode.Role.ERROR;
            case AWAITING_RECONNECT -> WidgetNode.Role.MUTED;
            case DISABLED -> WidgetNode.Role.MUTED;
        };
    }
}
