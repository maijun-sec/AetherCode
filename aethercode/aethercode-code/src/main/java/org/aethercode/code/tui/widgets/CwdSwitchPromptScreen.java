package org.aethercode.code.tui.widgets;

import java.util.List;

/**
 * Prompt for switching cwd when resuming or switching threads.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.cwd_switch}. The
 * Python module exposes a {@code CwdSwitchPromptScreen} {@code ModalScreen}
 * that asks the user whether to switch cwd when resuming or switching
 * to a thread. The dismiss contract is one of {@code "switch"},
 * {@code "stay"}, or {@code "abort"}.</p>
 *
 * <p>Also exposes {@code HookTrustScreen} — a sibling modal that asks
 * how project hooks in a newly-entered workspace should be trusted.
 * The Java port bundles them in one file because they share
 * styling and the abort-deferral logic.</p>
 */
public class CwdSwitchPromptScreen extends ModalScreen<CwdSwitchPromptScreen.CwdSwitchChoice> {

    /** Outcome of the cwd switch prompt. */
    public enum CwdSwitchChoice { SWITCH, STAY, ABORT }

    /** Which flow opened an abort-capable prompt, selecting the abort wording. */
    public enum CwdSwitchAbortMode { RESUME, THREAD_SWITCH }

    private final String currentCwd;
    private final String threadCwd;
    private final boolean projectSettingsChangeDetected;
    private final CwdSwitchAbortMode abort;

    public CwdSwitchPromptScreen(String currentCwd, String threadCwd,
                                 boolean projectSettingsChangeDetected,
                                 CwdSwitchAbortMode abort) {
        super("", "cwd-switch-prompt-screen");
        this.currentCwd = currentCwd;
        this.threadCwd = threadCwd;
        this.projectSettingsChangeDetected = projectSettingsChangeDetected;
        this.abort = abort;
    }

    public String currentCwd() { return currentCwd; }
    public String threadCwd() { return threadCwd; }
    public boolean projectSettingsChangeDetected() { return projectSettingsChangeDetected; }
    public CwdSwitchAbortMode abort() { return abort; }

    /** Return the title, phrased for the flow that opened the prompt. */
    public String titleText() {
        if (abort == null || abort == CwdSwitchAbortMode.RESUME) {
            return "Resume from the thread's original directory?";
        }
        return "Switch to the thread's original directory?";
    }

    /** Return the prompt body text. */
    public String bodyText() {
        String current = currentCwd;
        String target = threadCwd;
        String settingsNote = projectSettingsChangeDetected
                ? "\n\nSwitching may also reload project-specific config like .env, "
                  + "MCP, skills, and AGENTS.md."
                : "";
        String abortNote = "";
        if (abort == CwdSwitchAbortMode.RESUME) {
            abortNote = "\n\nOr abort to start a new session instead of resuming.";
        }
        return "This thread was last used from:\n  " + target
                + "\n\nYou're currently in:\n  " + current
                + "\n\nSwitch if you want local context, project instructions, skills, "
                + "MCP config, and env files to match the original directory. Stay "
                + "here if you intentionally want to continue this thread against "
                + "the current directory." + settingsNote + abortNote;
    }

    /** Return the help line text, naming the mode's abort action if offered. */
    public String helpText() {
        String help = "Enter: switch · Esc: stay in cwd";
        if (abort == null) return help;
        if (abort == CwdSwitchAbortMode.RESUME) return help + " · A: don't resume";
        return help + " · A: don't switch";
    }

    @Override
    public WidgetNode render() {
        WidgetNode title = new WidgetNode.Static(titleText(),
                WidgetNode.Role.WARNING, false, true, false);
        WidgetNode body = new WidgetNode.Static(bodyText(), WidgetNode.Role.TEXT);
        WidgetNode help = new WidgetNode.Static(helpText(),
                WidgetNode.Role.MUTED, true, false, true);
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL,
                List.of(title, body, help), "cwd-switch-prompt-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.priority("enter", "switch", "Switch"),
                KeyBinding.priority("escape", "stay", "Stay"),
                KeyBinding.priority("a", "abort", "Abort"),
                KeyBinding.priority("ctrl+c", "quit_or_interrupt", "Quit/Interrupt"),
                KeyBinding.priority("ctrl+d", "quit_app", "Quit"));
    }

    /**
     * Disable the {@code abort} binding unless the prompt was opened with
     * an {@code abort} mode set.
     */
    public boolean checkAction(String action) {
        if ("abort".equals(action)) return abort != null;
        return true;
    }

    public void actionSwitch() { dismiss(CwdSwitchChoice.SWITCH); }
    public void actionStay() { dismiss(CwdSwitchChoice.STAY); }
    public void actionAbort() {
        if (abort == null) return;
        dismiss(CwdSwitchChoice.ABORT);
    }
    public void actionCancel() { actionStay(); }
    public void actionQuitOrInterrupt() { /* delegate to app */ }
    public void actionQuitApp() { /* delegate to app */ }
}
