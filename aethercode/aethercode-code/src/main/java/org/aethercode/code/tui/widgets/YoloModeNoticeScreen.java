package org.aethercode.code.tui.widgets;

import java.util.List;

/**
 * First-enable confirmation modal before Shift+Tab enters YOLO.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.yolo_mode_notice}.
 * The Python module exposes a {@code YoloModeNoticeResult} enum and a
 * {@code YoloModeNoticeScreen} {@code ModalScreen} that is shown when
 * the user cycles into unrestricted YOLO without a persisted
 * acknowledgement.</p>
 */
public class YoloModeNoticeScreen extends ModalScreen<YoloModeNoticeScreen.YoloModeNoticeResult> {

    /** Outcome of the YOLO first-enable notice. */
    public enum YoloModeNoticeResult { ACKNOWLEDGE, MANUAL, CANCEL }

    /** Canonical docs page for Manual / Auto / YOLO behavior. */
    public static final String YOLO_MODE_DOCS_URL =
            "https://docs.langchain.com/oss/python/deepagents/code/approval-modes";

    /** Default Markdown body shown before the first YOLO switcher enable. */
    public static final String YOLO_MODE_NOTICE_BODY =
            "You are about to enable **YOLO mode**. The agent may run shell commands, "
            + "edit files, make network calls, and use other tools on this machine "
            + "**without asking you first**.\n\n"
            + "Only continue if you're comfortable letting it act unsupervised.\n\n"
            + "Leave YOLO any time with **Shift+Tab**.\n\n"
            + "This notice appears **once** on this machine.\n\n"
            + "[Learn more about approval modes](" + YOLO_MODE_DOCS_URL + ")";

    private final String body;

    public YoloModeNoticeScreen() {
        this(null);
    }

    public YoloModeNoticeScreen(String body) {
        super("", "yolo-mode-notice-screen");
        this.body = body == null ? YOLO_MODE_NOTICE_BODY : body;
    }

    public String body() { return body; }

    @Override
    public WidgetNode render() {
        WidgetNode title = new WidgetNode.Static("YOLO mode",
                WidgetNode.Role.ERROR, false, true, false);
        WidgetNode bodyNode = new WidgetNode.Markdown(body, "yolo-mode-notice-body");
        WidgetNode help = new WidgetNode.Static(
                "Enter to enable YOLO · m for Manual · Esc to keep current mode",
                WidgetNode.Role.MUTED, true, false, true);
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL,
                List.of(title, bodyNode, help),
                "yolo-mode-notice-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.priority("enter", "confirm", "Enable YOLO"),
                KeyBinding.priority("m", "switch_to_manual", "Switch to Manual"),
                KeyBinding.priority("escape", "cancel", "Keep previous"));
    }

    public void actionConfirm() { dismiss(YoloModeNoticeResult.ACKNOWLEDGE); }
    public void actionSwitchToManual() { dismiss(YoloModeNoticeResult.MANUAL); }
    public void actionCancel() { dismiss(YoloModeNoticeResult.CANCEL); }
}
