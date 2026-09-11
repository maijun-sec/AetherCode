package org.aethercode.code.tui.widgets;

import java.util.List;

/**
 * Confirmation modal offered when a change needs an owned-server respawn.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.restart_prompt}. The
 * Python {@code RestartPromptScreen} is a Textual {@code ModalScreen}
 * that asks the user whether to restart the LangGraph server subprocess
 * now or later. The title verb and body copy are caller-supplied so one
 * modal serves both the post-install and post-{@code /auth} flows.</p>
 *
 * <p>The Java port preserves the {@code RestartChoice} contract
 * ({@code "restart"} or {@code "later"}) and returns a
 * {@link WidgetNode.Container} from {@link #render()} with the
 * title, body, and help footer.</p>
 */
public class RestartPromptScreen extends ModalScreen<RestartPromptScreen.RestartChoice> {

    /** Outcome of the prompt. */
    public enum RestartChoice { RESTART, LATER }

    /** Default body shown when the caller does not provide one. */
    public static final String DEFAULT_BODY = "Restart the server to load it now.";

    private final String label;
    private final String verb;
    private final String body;

    /**
     * @param label The subject surfaced in the title.
     * @param verb  Past-tense action shown before {@code label} in the title.
     * @param body  Optional override for the explanatory line; defaults to
     *              {@link #DEFAULT_BODY}.
     */
    public RestartPromptScreen(String label, String verb, String body) {
        super("", "restart-prompt-screen");
        this.label = label;
        this.verb = verb;
        this.body = body == null || body.isEmpty() ? DEFAULT_BODY : body;
    }

    public String label() { return label; }
    public String verb() { return verb; }
    public String body() { return body; }

    @Override
    public WidgetNode render() {
        WidgetNode title = new WidgetNode.Static(
                checkGlyph() + " " + verb + " " + label,
                WidgetNode.Role.PRIMARY, false, true, false);
        WidgetNode bodyNode = new WidgetNode.Static(body, WidgetNode.Role.TEXT);
        WidgetNode help = new WidgetNode.Static(
                "Enter to restart, Esc to defer",
                WidgetNode.Role.MUTED, true, false, true);
        return new WidgetNode.Container(
                WidgetNode.Layout.VERTICAL,
                List.of(title, bodyNode, help),
                "restart-prompt-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.priority("enter", "restart", "Restart"),
                KeyBinding.priority("escape", "later", "Later"));
    }

    /** Dismiss with {@code "restart"}. */
    public void actionRestart() { dismiss(RestartChoice.RESTART); }

    /** Dismiss with {@code "later"}. */
    public void actionLater() { dismiss(RestartChoice.LATER); }

    /** Alias for {@link #actionLater()} so the app-level Esc binding defers. */
    public void actionCancel() { actionLater(); }

    private static String checkGlyph() {
        return "✓";
    }
}
