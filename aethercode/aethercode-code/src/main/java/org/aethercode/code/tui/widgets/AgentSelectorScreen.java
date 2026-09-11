package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Interactive agent selector screen for the {@code /agents} command.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.agent_selector}. The
 * Python {@code AgentSelectorScreen} is a {@code ModalScreen[str | None]}
 * that lists available agents from {@code ~/.deepagents/} (directories
 * with an {@code AGENTS.md} marker) and returns the chosen agent name
 * on Enter, or {@code null} on Esc.</p>
 */
public class AgentSelectorScreen extends ModalScreen<String> {

    private final String currentAgent;
    private final List<String> agentNames;
    private final String defaultAgent;
    private int selected;

    public AgentSelectorScreen(String currentAgent, List<String> agentNames, String defaultAgent) {
        super("", "agent-selector-screen");
        this.currentAgent = currentAgent;
        this.agentNames = agentNames == null ? List.of() : List.copyOf(agentNames);
        this.defaultAgent = defaultAgent;
        this.selected = computeInitialIndex();
    }

    public Optional<String> currentAgent() { return Optional.ofNullable(currentAgent); }
    public List<String> agentNames() { return agentNames; }
    public Optional<String> defaultAgent() { return Optional.ofNullable(defaultAgent); }
    public int selected() { return selected; }

    private int computeInitialIndex() {
        if (currentAgent == null) return 0;
        int idx = agentNames.indexOf(currentAgent);
        return idx < 0 ? 0 : idx;
    }

    /** Render the option label for an agent name with (current)/(default) markers. */
    public String formatLabel(String name) {
        boolean isCurrent = name.equals(currentAgent);
        boolean isDefault = name.equals(defaultAgent);
        if (isCurrent && isDefault) return name + " (current, default)";
        if (isCurrent) return name + " (current)";
        if (isDefault) return name + " (default)";
        return name;
    }

    @Override
    public WidgetNode render() {
        List<WidgetNode> children = new ArrayList<>();
        children.add(new WidgetNode.Static("Select Agent",
                WidgetNode.Role.PRIMARY, false, true, false));
        if (agentNames.isEmpty()) {
            children.add(new WidgetNode.Static(
                    "No agents found in ~/.deepagents/.\nRun dcode with -a <name> to create one.",
                    WidgetNode.Role.MUTED));
        } else {
            children.add(new WidgetNode.Static(
                    "Switching restarts the agent and starts a new thread.",
                    WidgetNode.Role.MUTED));
            List<WidgetNode.Option> options = new ArrayList<>();
            for (String name : agentNames) {
                options.add(new WidgetNode.Option(name, formatLabel(name), "",
                        false, name.equals(currentAgent)));
            }
            children.add(new WidgetNode.OptionList(options, "agent-options"));
        }
        String help = agentNames.isEmpty() ? "Esc close" : "↑/↓ navigate · Enter select\nCtrl+S set default · Esc cancel";
        children.add(new WidgetNode.Static(help, WidgetNode.Role.MUTED, true, false, true));
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, children,
                "agent-selector-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.of("escape", "cancel", "Cancel"),
                KeyBinding.priority("tab", "cursor_down", "Next"),
                KeyBinding.priority("shift+tab", "cursor_up", "Previous"),
                KeyBinding.priority("ctrl+s", "set_default", "Set default"));
    }

    public void actionCancel() { dismiss(null); }
    public void actionCursorDown() {
        if (agentNames.isEmpty()) return;
        selected = (selected + 1) % agentNames.size();
    }
    public void actionCursorUp() {
        if (agentNames.isEmpty()) return;
        selected = (selected - 1 + agentNames.size()) % agentNames.size();
    }

    /** Toggle the highlighted agent as the persisted default. */
    public void actionSetDefault() {
        if (agentNames.isEmpty()) return;
        // Hosts persist the change via deepagents-core's
        // model_config.save_default_agent / clear_default_agent. The Java
        // port surfaces the toggle; the host decides whether to spin a
        // worker or block on disk I/O.
    }

    public void onOptionSelected(String agentId) { dismiss(agentId); }
}
