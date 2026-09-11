package org.aethercode.code.tui.widgets;

import java.util.List;

/**
 * Trust prompt for skills that resolve outside trusted skill directories.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.skill_trust}. The
 * Python {@code SkillTrustScreen} is a non-blocking {@code ModalScreen[bool | None]}
 * shown when {@code /skill:<name>} reads a {@code SKILL.md} whose
 * resolved path falls outside every trusted skill root. Allowing persists
 * the resolved target directory to the skill trust store.</p>
 *
 * <p>The Java port preserves the {@code Boolean} dismiss contract
 * ({@code true} = allow, {@code false} = deny, {@code null} = cancel).
 * {@link #dismissNone()} corresponds to the {@code None} case which the
 * caller should treat like {@code false} per the Python docstring.</p>
 */
public class SkillTrustScreen extends ModalScreen<Boolean> {

    private final String skillName;
    private final String targetDir;

    public SkillTrustScreen(String skillName, String targetDir) {
        super("", "skill-trust-screen");
        this.skillName = skillName;
        this.targetDir = targetDir;
    }

    public String skillName() { return skillName; }
    public String targetDir() { return targetDir; }

    @Override
    public WidgetNode render() {
        WidgetNode title = new WidgetNode.Static(
                "Allow skill from outside trusted directories?",
                WidgetNode.Role.WARNING, false, true, false);
        String body = "Skill " + skillName + " resolves to " + targetDir
                + ", outside your trusted skill directories. Allowing reads "
                + "instructions from there and remembers this location for "
                + "future sessions.";
        WidgetNode bodyNode = new WidgetNode.Static(body, WidgetNode.Role.TEXT);
        WidgetNode help = new WidgetNode.Static("Enter to allow, Esc to deny",
                WidgetNode.Role.MUTED, true, false, true);
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL,
                List.of(title, bodyNode, help),
                "skill-trust-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.priority("enter", "confirm", "Allow"),
                KeyBinding.priority("escape", "cancel", "Deny"));
    }

    public void actionConfirm() { dismiss(Boolean.TRUE); }

    /**
     * The method name must stay {@code cancel} — the app owns a priority
     * {@code escape} binding that, for an active {@code ModalScreen},
     * dispatches to {@code action_cancel} if present. Renaming would
     * silently regress Esc to a {@code null} dismiss.
     */
    public void actionCancel() { dismiss(Boolean.FALSE); }
}
