package org.aethercode.code.tui.widgets;

import java.util.List;

/**
 * Project-hooks trust prompt shown when entering a new workspace.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.cwd_switch.HookTrustScreen}.
 * The Python module reuses {@code CwdSwitchPromptScreen.CSS} (with a
 * class-name swap and a width bump) and the same
 * {@code check_action} / {@code action_cancel} → {@code action_deny}
 * pattern.</p>
 */
public class HookTrustScreen extends ModalScreen<HookTrustScreen.HookTrustChoice> {

    /** Outcome of the project-hooks trust prompt. */
    public enum HookTrustChoice { ALLOW_ONCE, ALWAYS_ALLOW, DENY }

    private final String projectRoot;
    private final String configPath;

    public HookTrustScreen(String projectRoot, String configPath) {
        super("", "hook-trust-screen");
        this.projectRoot = projectRoot;
        this.configPath = configPath;
    }

    public String projectRoot() { return projectRoot; }
    public String configPath() { return configPath; }

    @Override
    public WidgetNode render() {
        WidgetNode title = new WidgetNode.Static(
                "Project hooks can run arbitrary shell commands on your machine",
                WidgetNode.Role.WARNING, false, true, false);
        String body = projectRoot + " contains project hooks at " + configPath
                + ". Only trust projects you control. \"Allow once\" runs the file "
                + "as it is now; \"always allow\" trusts " + projectRoot
                + " for future sessions and future edits.";
        WidgetNode bodyNode = new WidgetNode.Static(body, WidgetNode.Role.TEXT);
        WidgetNode help = new WidgetNode.Static(
                "Enter: allow once · A: always allow · Esc: deny",
                WidgetNode.Role.MUTED, true, false, true);
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL,
                List.of(title, bodyNode, help),
                "hook-trust-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.priority("enter", "allow_once", "Allow once"),
                KeyBinding.priority("a", "always_allow", "Always allow"),
                KeyBinding.priority("escape", "deny", "Deny"));
    }

    public void actionAllowOnce() { dismiss(HookTrustChoice.ALLOW_ONCE); }
    public void actionAlwaysAllow() { dismiss(HookTrustChoice.ALWAYS_ALLOW); }
    public void actionDeny() { dismiss(HookTrustChoice.DENY); }
    public void actionCancel() { actionDeny(); }
}
