/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
