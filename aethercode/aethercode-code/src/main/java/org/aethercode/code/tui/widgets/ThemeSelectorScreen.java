package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Interactive theme selector screen for the {@code /theme} command.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.theme_selector}. The
 * Python {@code ThemeSelectorScreen} is a {@code ModalScreen[str | None]}
 * with live preview: navigating the option list applies the active
 * theme on the fly, and Enter commits the choice. Esc restores the
 * original theme unless a {@code t} save set a per-terminal default.</p>
 */
public class ThemeSelectorScreen extends ModalScreen<String> {

    /** One entry in the theme registry. Hosts inject the concrete
     *  {@code deepagents_code.theme.ThemeEntry} shape. */
    public record ThemeEntry(String label) {}

    private final String currentTheme;
    private final String terminalDefault;
    private final String originalTheme;
    private String sessionTerminalDefault;
    private String cancelKeptTerminalDefault;
    private boolean showKeys;

    public ThemeSelectorScreen(String currentTheme, String terminalDefault) {
        super("", "theme-selector-screen");
        this.currentTheme = currentTheme;
        this.originalTheme = currentTheme;
        this.terminalDefault = terminalDefault;
    }

    public String currentTheme() { return currentTheme; }
    public String terminalDefault() { return terminalDefault; }
    public String originalTheme() { return originalTheme; }

    /** Render the option text for a theme entry. */
    public String formatOption(String name, ThemeEntry entry) {
        String text = showKeys ? name : (entry == null ? name : entry.label());
        List<String> suffixes = new ArrayList<>();
        if (name.equals(currentTheme)) suffixes.add("current");
        if (name.equals(terminalDefault)) suffixes.add("default");
        if (!suffixes.isEmpty()) text = text + " (" + String.join(", ", suffixes) + ")";
        return text;
    }

    @Override
    public WidgetNode render() {
        List<WidgetNode.Option> options = new ArrayList<>();
        Map<String, ThemeEntry> registry = ThemeRegistry.SNAPSHOT;
        for (Map.Entry<String, ThemeEntry> e : registry.entrySet()) {
            options.add(new WidgetNode.Option(e.getKey(),
                    formatOption(e.getKey(), e.getValue()),
                    "", false, e.getKey().equals(currentTheme)));
        }
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                new WidgetNode.Static("Select Theme",
                        WidgetNode.Role.PRIMARY, false, true, false),
                new WidgetNode.OptionList(options, "theme-options"),
                new WidgetNode.Static(
                        "↑/↓ navigate · Enter select · Esc cancel\n"
                                + "N labels/keys · T set for this terminal",
                        WidgetNode.Role.MUTED, true, false, true)
        ), "theme-selector-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.of("escape", "cancel", "Cancel"),
                KeyBinding.priority("tab", "cursor_down", "Next"),
                KeyBinding.priority("shift+tab", "cursor_up", "Previous"),
                KeyBinding.of("n", "toggle_names", "Names"),
                KeyBinding.of("t", "set_for_terminal", "Set for terminal"));
    }

    public void actionCancel() {
        // If sessionTerminalDefault is set, keep that theme. Otherwise
        // restore originalTheme. The host applies the resulting theme.
        String keep = sessionTerminalDefault;
        if (keep != null) cancelKeptTerminalDefault = keep;
        dismiss(null);
    }

    public void actionCursorDown() { /* host advances OptionList cursor */ }
    public void actionCursorUp() { /* host moves OptionList cursor up */ }
    public void actionToggleNames() { this.showKeys = !this.showKeys; }
    public void actionSetForTerminal() { /* host persists via app.runWorker */ }

    public void onOptionSelected(String name) {
        if (name != null) dismiss(name);
    }

    /** Snapshot of the theme registry. The host injects the real one. */
    public static final class ThemeRegistry {
        public static volatile Map<String, ThemeEntry> SNAPSHOT = Map.of();
        private ThemeRegistry() {}
    }
}
