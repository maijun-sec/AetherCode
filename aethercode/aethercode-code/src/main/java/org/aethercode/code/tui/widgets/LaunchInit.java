package org.aethercode.code.tui.widgets;

import java.util.List;

/**
 * Onboarding screens for the interactive TUI.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.launch_init}. The
 * Python module exposes three onboarding screens:
 * {@code LaunchGoalCriteriaPreferenceScreen},
 * {@code LaunchNameScreen}, and {@code LaunchDependenciesScreen}. The
 * Java port preserves the public surface and dismiss contracts.</p>
 */
public final class LaunchInit {

    private LaunchInit() {}

    /** One dependency status entry shown in the launch screen. */
    public record ExtraDependencyStatus(String extra, String description, boolean installed) {}

    /**
     * One-time choice for how Auto mode handles generated goal criteria.
     */
    public static final class GoalCriteriaPreferenceScreen extends ModalScreen<Boolean> {
        public GoalCriteriaPreferenceScreen() { super("", "launch-goal-criteria-screen"); }

        @Override
        public WidgetNode render() {
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                    new WidgetNode.Static("How should Auto mode handle goal criteria?",
                            WidgetNode.Role.PRIMARY, false, true, false),
                    new WidgetNode.Static(
                            "When you create or update a goal, dcode drafts acceptance "
                                    + "criteria before starting."),
                    new WidgetNode.OptionList(List.of(
                            new WidgetNode.Option("review", "Review before applying (recommended)", "", false, true),
                            new WidgetNode.Option("auto", "Apply automatically in Auto mode", "", false, false)
                    ), "launch-goal-criteria-options"),
                    new WidgetNode.Static(
                            "You can change this at any time in ~/.deepagents/config.toml "
                                    + "or with DEEPAGENTS_CODE_GOAL_AUTO_ACCEPT_CRITERIA.",
                            WidgetNode.Role.MUTED),
                    new WidgetNode.Static("↑/↓ navigate · Enter select · Esc review",
                            WidgetNode.Role.MUTED, true, false, true)
            ), "launch-goal-criteria-screen");
        }

        @Override
        public List<KeyBinding> bindings() {
            return List.of(
                    KeyBinding.priority("escape", "review", "Review"),
                    KeyBinding.priority("tab", "cursor_down", "Next"),
                    KeyBinding.priority("shift+tab", "cursor_up", "Previous"));
        }

        public void actionReview() { dismiss(Boolean.FALSE); }
        public void actionCancel() { actionReview(); }
        public void actionCursorDown() { /* host advances option list */ }
        public void actionCursorUp() { /* host moves option list up */ }

        public void onOptionSelected(String id) {
            if ("auto".equals(id)) dismiss(Boolean.TRUE);
            else if ("review".equals(id)) dismiss(Boolean.FALSE);
        }
    }

    /**
     * Onboarding screen that asks for the user's name.
     */
    public static final class NameScreen extends ModalScreen<String> {
        public NameScreen() { super("", "launch-name-screen"); }

        @Override
        public WidgetNode render() {
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                    new WidgetNode.Static("Welcome to Deep Agents Code",
                            WidgetNode.Role.PRIMARY, false, true, false),
                    new WidgetNode.Static("What should Deep Agents call you?"),
                    new WidgetNode.Input("launch-name-input", "Your name (optional)"),
                    new WidgetNode.Static("Enter to continue",
                            WidgetNode.Role.MUTED, true, false, true)
            ), "launch-name-screen");
        }

        @Override
        public List<KeyBinding> bindings() {
            return List.of(KeyBinding.priority("escape", "skip", "Skip"));
        }

        public void onInputSubmitted(String value) {
            String normalized = normalizeName(value);
            dismiss(normalized);
        }

        public void actionSkip() { dismiss(null); }
        public void actionCancel() { actionSkip(); }

        public static String normalizeName(String value) {
            if (value == null) return "";
            String stripped = value.strip();
            if (stripped.isEmpty()) return "";
            if (stripped.chars().allMatch(Character::isLowerCase)) {
                return Character.toUpperCase(stripped.charAt(0))
                        + stripped.substring(1);
            }
            return stripped;
        }
    }

    /**
     * Onboarding screen that summarizes installed optional integrations.
     */
    public static final class DependenciesScreen extends ModalScreen<Boolean> {
        private final List<ExtraDependencyStatus> statuses;

        public DependenciesScreen(List<ExtraDependencyStatus> statuses) {
            super("", "launch-dependencies-screen");
            this.statuses = statuses == null ? List.of() : List.copyOf(statuses);
        }

        public List<ExtraDependencyStatus> statuses() { return statuses; }

        @Override
        public WidgetNode render() {
            WidgetNode.Section readySection = new WidgetNode.Section("Ready now");
            WidgetNode.Section availableSection = new WidgetNode.Section("Available to add");
            int ready = 0;
            for (LaunchInit.ExtraDependencyStatus s : statuses) {
                WidgetNode row = new WidgetNode.Static(
                        (s.installed() ? "✓ " : "○ ") + s.description(),
                        s.installed() ? WidgetNode.Role.SUCCESS : WidgetNode.Role.MUTED);
                if (s.installed()) {
                    readySection = readySection.withChild(row);
                    ready++;
                } else {
                    availableSection = availableSection.withChild(row);
                }
            }
            if (ready == 0) {
                readySection = readySection.withChild(new WidgetNode.Static(
                        "Nothing installed yet — add one below.", WidgetNode.Role.MUTED));
            }
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                    new WidgetNode.Static("Installed Integrations",
                            WidgetNode.Role.PRIMARY, false, true, false),
                    new WidgetNode.Static(
                            "Model providers and sandboxes are enabled by optional add-on "
                                    + "packages. The ones already present in your environment are "
                                    + "ready to use now.", WidgetNode.Role.MUTED),
                    readySection,
                    availableSection,
                    new WidgetNode.Static(
                            "Pick a model on the next screen and its provider installs "
                                    + "automatically. Add more anytime with /install."),
                    new WidgetNode.Static("Enter to continue",
                            WidgetNode.Role.MUTED, true, false, true)
            ), "launch-dependencies-screen");
        }

        @Override
        public List<KeyBinding> bindings() {
            return List.of(
                    KeyBinding.priority("enter", "continue", "Continue"),
                    KeyBinding.priority("escape", "skip", "Skip"));
        }

        public void actionContinue() { dismiss(Boolean.TRUE); }
        public void actionSkip() { dismiss(null); }
    }
}
