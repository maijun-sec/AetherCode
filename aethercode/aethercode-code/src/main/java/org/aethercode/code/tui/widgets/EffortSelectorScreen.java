package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive reasoning effort selector for {@code /effort}.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.effort_selector}. The
 * Python {@code EffortSelectorScreen} is a {@code ModalScreen[str | None]}
 * that lists the supported effort labels for the active
 * {@code provider:model} and dismisses with the chosen label or
 * {@code null}.</p>
 */
public class EffortSelectorScreen extends ModalScreen<String> {

    private final String modelSpec;
    private final List<String> efforts;
    private final String currentEffort;
    private final String defaultEffort;
    private int selected = 0;

    public EffortSelectorScreen(String modelSpec, List<String> efforts,
                                String currentEffort, String defaultEffort) {
        super("", "effort-selector-screen");
        this.modelSpec = modelSpec;
        this.efforts = efforts == null ? List.of() : List.copyOf(efforts);
        this.currentEffort = currentEffort;
        this.defaultEffort = defaultEffort;
        this.selected = computeInitialSelection();
    }

    public String modelSpec() { return modelSpec; }
    public List<String> efforts() { return efforts; }
    public String currentEffort() { return currentEffort; }
    public String defaultEffort() { return defaultEffort; }
    public int selected() { return selected; }

    private int computeInitialSelection() {
        String highlighted = currentEffort != null ? currentEffort : defaultEffort;
        if (highlighted != null) {
            for (int i = 0; i < efforts.size(); i++) {
                if (highlighted.equals(efforts.get(i))) return i;
            }
        }
        return 0;
    }

    /** Render the label for an effort, including (current) / (default) markers. */
    public WidgetNode renderOption(String effort) {
        List<String> markers = new ArrayList<>();
        if (effort.equals(currentEffort)) markers.add("current");
        if (effort.equals(defaultEffort)) markers.add("default");
        if (markers.isEmpty()) {
            return new WidgetNode.Static(effort);
        }
        String suffix = String.join(", ", markers);
        return new WidgetNode.Static(effort + " (" + suffix + ")",
                WidgetNode.Role.MUTED, true, false, false);
    }

    @Override
    public WidgetNode render() {
        List<WidgetNode.Option> options = new ArrayList<>();
        for (int i = 0; i < efforts.size(); i++) {
            String effort = efforts.get(i);
            String label;
            if (i == selected) {
                WidgetNode n = renderOption(effort);
                label = n.toString();
            } else {
                label = renderOption(effort).toString();
            }
            options.add(new WidgetNode.Option(effort, label, "", false, i == selected));
        }
        WidgetNode optionList = new WidgetNode.OptionList(options, "effort-options");
        WidgetNode title = new WidgetNode.Static("Select Reasoning Effort",
                WidgetNode.Role.PRIMARY, false, true, false);
        WidgetNode subtitle = new WidgetNode.Static(modelSpec, WidgetNode.Role.MUTED);
        WidgetNode help = new WidgetNode.Static(
                "↑/↓ navigate · Enter select · Esc cancel",
                WidgetNode.Role.MUTED, true, false, true);
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL,
                List.of(title, subtitle, optionList, help),
                "effort-selector-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.of("escape", "cancel", "Cancel"),
                KeyBinding.priority("tab", "cursor_down", "Next"),
                KeyBinding.priority("shift+tab", "cursor_up", "Previous"));
    }

    public void actionCancel() { dismiss(null); }
    public void actionCursorDown() {
        if (efforts.isEmpty()) return;
        selected = (selected + 1) % efforts.size();
    }
    public void actionCursorUp() {
        if (efforts.isEmpty()) return;
        selected = (selected - 1 + efforts.size()) % efforts.size();
    }

    /** Invoked when the user picks an option. Substitutes the Python event handler. */
    public void onOptionSelected(String effortId) {
        if (effortId != null) dismiss(effortId);
    }
}
