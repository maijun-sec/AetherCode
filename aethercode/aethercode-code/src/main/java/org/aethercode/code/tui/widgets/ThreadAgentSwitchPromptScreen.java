package org.aethercode.code.tui.widgets;

import java.util.List;

/**
 * Confirmation prompt for resuming a thread owned by another agent.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.thread_agent_switch}.
 * The Python {@code ThreadAgentSwitchPromptScreen} is a
 * {@code ModalScreen[ThreadAgentSwitchChoice]} asking whether to switch
 * agents when resuming a thread owned by a different agent.</p>
 *
 * <p>The Java port preserves the {@code ThreadAgentSwitchChoice} contract
 * ({@code "switch"} or {@code "cancel"}) and the dismiss bindings.</p>
 */
public class ThreadAgentSwitchPromptScreen extends ModalScreen<ThreadAgentSwitchPromptScreen.ThreadAgentSwitchChoice> {

    /** Outcome of the cross-agent thread resume prompt. */
    public enum ThreadAgentSwitchChoice { SWITCH, CANCEL }

    private final String threadId;
    private final String currentAgent;
    private final String threadAgent;

    public ThreadAgentSwitchPromptScreen(String threadId, String currentAgent, String threadAgent) {
        super("", "thread-agent-switch-screen");
        this.threadId = threadId;
        this.currentAgent = currentAgent;
        this.threadAgent = threadAgent;
    }

    public String threadId() { return threadId; }
    public String currentAgent() { return currentAgent; }
    public String threadAgent() { return threadAgent; }

    /** Plain-text prompt body. */
    public String bodyText() {
        return "Thread " + threadId + " belongs to agent " + threadAgent
                + ", but " + currentAgent + " is active.\n\n"
                + "Switch to " + threadAgent + " and resume this thread? The local "
                + "agent server will restart. Your saved default agent will not change.";
    }

    @Override
    public WidgetNode render() {
        WidgetNode title = new WidgetNode.Static("Switch agents to resume?",
                WidgetNode.Role.WARNING, false, true, false);
        WidgetNode body = new WidgetNode.Static(bodyText(), WidgetNode.Role.TEXT);
        WidgetNode help = new WidgetNode.Static("Enter: switch and resume · Esc: cancel",
                WidgetNode.Role.MUTED, true, false, true);
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL,
                List.of(title, body, help),
                "thread-agent-switch-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.priority("enter", "switch", "Switch"),
                KeyBinding.priority("escape", "cancel", "Cancel"),
                KeyBinding.priority("ctrl+c", "quit_or_interrupt", "Quit/Interrupt"),
                KeyBinding.priority("ctrl+d", "quit_app", "Quit"));
    }

    public void actionSwitch() { dismiss(ThreadAgentSwitchChoice.SWITCH); }
    public void actionCancel() { dismiss(ThreadAgentSwitchChoice.CANCEL); }
    public void actionQuitOrInterrupt() { /* delegate to app */ }
    public void actionQuitApp() { /* delegate to app */ }
}
