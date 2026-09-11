package org.aethercode.code.tui.widgets;

import java.util.List;

/**
 * First-enable confirmation modal for Auto mode.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.auto_mode_notice}.
 * The Python module exposes a default Markdown body and a builder
 * {@code build_auto_mode_notice_body} that interpolates the reviewing
 * model's display name. The {@code AutoModeNoticeScreen} is a
 * {@code ModalScreen[bool]} that dismisses with {@code true} on Enter
 * (confirm) and {@code false} on Esc (cancel).</p>
 */
public class AutoModeNoticeScreen extends ModalScreen<Boolean> {

    /** Canonical docs page for Manual / Auto / YOLO behavior. */
    public static final String AUTO_MODE_DOCS_URL =
            "https://docs.langchain.com/oss/python/deepagents/code/approval-modes";

    /** Phrase in {@link #AUTO_MODE_NOTICE_BODY} that the model description replaces. */
    public static final String AUTO_MODE_NOTICE_MODEL_ANCHOR = "**classifier model**";

    /** Default Markdown body shown on first successful Auto enable. */
    public static final String AUTO_MODE_NOTICE_BODY =
            "You switched to **Auto**. The agent can approve **routine gated actions** "
            + "without asking first — for example, file edits and read-only Git "
            + "commands.\n\n"
            + "Anything uncertain is reviewed by "
            + AUTO_MODE_NOTICE_MODEL_ANCHOR + ". If review keeps failing, you're asked "
            + "to approve.\n\n"
            + "This is **not a sandbox**. The agent still runs on this machine and can "
            + "change files, run commands, and use tools when Auto allows them.\n\n"
            + "This notice appears **once** on this machine after you continue.\n\n"
            + "[Learn more about approval modes](" + AUTO_MODE_DOCS_URL + ")";

    private final String body;

    public AutoModeNoticeScreen() {
        this(null, null, null);
    }

    public AutoModeNoticeScreen(String body, String modelLabel, Boolean distinctFromMainModel) {
        super("", "auto-mode-notice-screen");
        if (body != null) {
            this.body = body;
        } else if (distinctFromMainModel != null) {
            this.body = buildBody(modelLabel, distinctFromMainModel);
        } else {
            this.body = AUTO_MODE_NOTICE_BODY;
        }
    }

    public String body() { return body; }

    /**
     * Build the notice body with the reviewing model's display name.
     *
     * <p>Mirrors the Python {@code build_auto_mode_notice_body} function.
     * Raises {@link IllegalStateException} if the interpolation anchor is
     * missing so the disclosure cannot silently drop.</p>
     */
    public static String buildBody(String modelLabel, boolean distinctFromMainModel) {
        if (!AUTO_MODE_NOTICE_BODY.contains(AUTO_MODE_NOTICE_MODEL_ANCHOR)) {
            throw new IllegalStateException(
                    "AUTO_MODE_NOTICE_BODY is missing the classifier-model anchor; "
                    + "the Auto classifier model would not be disclosed");
        }
        String named = (modelLabel != null && !modelLabel.isEmpty())
                ? " (" + modelLabel + ")" : "";
        String description;
        if (distinctFromMainModel) {
            description = "a separate " + AUTO_MODE_NOTICE_MODEL_ANCHOR + named
                    + " — not the model writing your code";
        } else {
            description = "the " + AUTO_MODE_NOTICE_MODEL_ANCHOR
                    + ", which is the same model writing your code" + named;
        }
        return AUTO_MODE_NOTICE_BODY.replace(AUTO_MODE_NOTICE_MODEL_ANCHOR, description);
    }

    @Override
    public WidgetNode render() {
        WidgetNode title = new WidgetNode.Static("Auto mode",
                WidgetNode.Role.WARNING, false, true, false);
        WidgetNode bodyNode = new WidgetNode.Markdown(body, "auto-mode-notice-body");
        WidgetNode help = new WidgetNode.Static(
                "Enter switch to Auto · Esc cancel",
                WidgetNode.Role.MUTED, true, false, true);
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL,
                List.of(title, bodyNode, help),
                "auto-mode-notice-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.priority("enter", "confirm", "Keep Auto"),
                KeyBinding.priority("escape", "cancel", "Manual"));
    }

    public void actionConfirm() { dismiss(Boolean.TRUE); }

    /** The method name must stay {@code cancel} for the same reason as {@code SkillTrustScreen}. */
    public void actionCancel() { dismiss(Boolean.FALSE); }
}
